# engineering-knowledge-mcp-exposure Specification

## Purpose
TBD - created by archiving change engineering-knowledge-mcp-exposure. Update Purpose after archive.
## Requirements
### Requirement: Local stdio-only MCP transport

The system SHALL expose the initial MCP server only over stdin/stdout of a locally launched process. It MUST NOT bind a network listener, contact a remote service, accept remote sessions/tokens, or emit non-protocol logs on stdout. Unknown or unregistered workspaces MUST be rejected without revealing absolute filesystem paths.

#### Scenario: Start the local MCP process

- **WHEN** an approved local parent process starts the MCP server through its explicit stdio entry point
- **THEN** the server communicates protocol messages only on stdin/stdout and creates no HTTP/TCP/SSE listener

#### Scenario: Request an unknown workspace

- **WHEN** a tool request names a workspace that is unknown or outside the registered workspace set
- **THEN** the server returns a sanitized `NOT_FOUND` or `INVALID_ARGUMENT`-class error without exposing an absolute path or filesystem detail

### Requirement: Stable read-only knowledge tool set

The server SHALL expose exactly these initial read-only tool names with versioned JSON schemas: `knowledge_search`, `knowledge_get`, `code_symbol`, `code_search`, `trace`, and `build_context`. Each tool MUST call only its corresponding `KnowledgeAgentFacade` application operation and MUST NOT directly use SQLite, Lucene, ripgrep, Ctags, Git, SQL, or command-line parameters.

#### Scenario: Search engineering knowledge

- **WHEN** `knowledge_search` receives a valid workspace, query, filters, and bounded result limit
- **THEN** it returns the facade's traceable hybrid retrieval items, freshness, diagnostics, and completeness state through its stable output schema

#### Scenario: Get a knowledge object

- **WHEN** `knowledge_get` receives a valid workspace and stable object ID
- **THEN** it returns only the allowed current object and its source/evidence or a sanitized `NOT_FOUND` result

#### Scenario: Find a code symbol

- **WHEN** `code_symbol` receives a valid workspace and bounded symbol query
- **THEN** it delegates to the facade's symbol-filtered application retrieval and returns current traceable symbol candidates without raw Ctags data

#### Scenario: Search exact code text

- **WHEN** `code_search` receives a valid workspace, literal query, controlled scope, and budget
- **THEN** it delegates to the facade's constrained code-search operation and returns bounded text evidence plus truncation/diagnostics without raw ripgrep flags

#### Scenario: Trace verified relations

- **WHEN** `trace` receives a valid workspace and stable start object ID
- **THEN** it returns only fixed-range, evidence-bearing relations with source/freshness information and does not accept arbitrary graph depth or raw Store query fields

#### Scenario: Build engineering context

- **WHEN** `build_context` receives a valid workspace, target, supported intent, and valid token budget
- **THEN** it delegates to `ContextBuilder` and returns the resulting budgeted items, provenance, omissions, diagnostics, and estimator information without generating an answer

### Requirement: Strict schema validation and compatible evolution

Every initial tool MUST validate required fields, types, enum values, workspace IDs, budgets, maximum request/response sizes, and unknown fields before calling the facade. Outputs MUST include the documented schema version and use stable IDs, workspace-relative locations, bounded excerpts/records, provenance, freshness, evidence, and structured diagnostics. Additive compatible fields are allowed; removals, renames, or semantic changes require a separate versioned change.

The initial protocol target SHALL be MCP `2025-11-25` using the approved official Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.1`, and the tool input/output schema version SHALL be `1.0`. Each request MUST name a workspace registered in the immutable application catalog. A protocol request MUST NOT exceed 65,536 UTF-8 bytes, a complete response MUST NOT exceed 1,048,576 UTF-8 bytes, and a tool call MUST NOT exceed a 10-second hard deadline. The facade MUST preserve smaller downstream limits and enforce tool ceilings of 100 retrieval/symbol items, 200 code-search items, 64 one-hop trace relations, and a 256–32,768 context token budget.

#### Scenario: Reject an over-budget or unknown schema field

- **WHEN** a request contains an unsupported field, invalid enum, malformed object ID, or a budget exceeding the tool limit
- **THEN** the server rejects it as `INVALID_ARGUMENT` without calling a lower-level Module

### Requirement: Sanitized error, timeout, and cancellation semantics

The transport and facade MUST map application outcomes to the controlled categories `INVALID_ARGUMENT`, `NOT_FOUND`, `UNAVAILABLE`, `INCOMPLETE`, `DEADLINE_EXCEEDED`, `CANCELLED`, and `INTERNAL`. They MUST enforce per-tool deadlines and response limits, propagate actual thread interruption without terminating the entire server, preserve partial-result diagnostics, and never expose stack traces, SQL, absolute paths, command lines, or secrets. Under the approved SDK 2.0.1 runtime, inbound MCP `notifications/cancelled` is best-effort and is not guaranteed to interrupt an executing tool; guaranteed protocol cancellation requires a separate runtime compatibility change.

#### Scenario: Return a partial result from an unavailable source

- **WHEN** an underlying application Module returns valid partial data plus an unavailable-source diagnostic
- **THEN** the tool returns the valid bounded data with `INCOMPLETE` diagnostics rather than hiding it or converting it to a raw internal error

#### Scenario: Bound or attempt to cancel a long-running tool call

- **WHEN** a call reaches its hard deadline, its execution thread is interrupted, or a client sends a best-effort cancellation notification
- **THEN** the transport interrupts deadline-bound work, maps an observed thread interruption to the controlled cancellation outcome, and remains available for later requests without a write side effect; SDK 2.0.1 MAY ignore the protocol cancellation notification

### Requirement: Read-only side-effect isolation

All six tools MUST be read-only. They MUST NOT trigger ingestion, index rebuild, database/schema write, file or Git mutation, network access, existing HTTP/CLI routing change, dependency change, or deployment/configuration change. `trace` MUST only expose evidence-bearing relations within its fixed range and must not infer relations from text, symbols, or commits.

#### Scenario: Invoke all tools against fixtures

- **WHEN** a focused harness invokes each initial tool against controlled facade fixtures
- **THEN** it observes only read operations and schema-shaped responses; no Store write, Lucene write, Git/file mutation, network connection, or existing HTTP/CLI route invocation occurs

### Requirement: Runtime dependency and lifecycle gate
The MCP server MUST use the approved official Java SDK runtime and MUST be constructed from the production single-workspace application composition. Startup MUST register the six tool handlers backed by `KnowledgeAgentFacade`; it MUST fail readiness rather than serving an unavailable placeholder handler. MCP lifecycle MUST remain independently disableable and MUST NOT change HTTP/CLI startup unless explicitly enabled by the new service configuration.

#### Scenario: Start production stdio MCP
- **WHEN** the operator enables MCP for a valid new-format service workspace
- **THEN** the server registers all six facade-backed read-only tools over stdio and no handler returns a configuration placeholder

#### Scenario: Composition cannot initialize
- **WHEN** the workspace or application composition cannot become ready
- **THEN** MCP startup fails without binding a network listener or advertising usable tools

### Requirement: MCP uses the implicit service workspace
MCP tool inputs MUST NOT accept `workspaceId`. Every tool SHALL operate on the one workspace owned by its service process while preserving path confinement, bounded output, provenance, freshness, and sanitization requirements.

#### Scenario: Invoke knowledge search
- **WHEN** a client invokes `knowledge_search` with a valid workspace-free request
- **THEN** the facade searches the service workspace and returns the existing versioned result contract

### Requirement: MCP knowledge search accepts an optional virtual project

The read-only `knowledge_search` tool MUST accept an optional virtual-project identifier as an additive field in its versioned input schema. When supplied, the facade MUST search shared knowledge and that project using the deterministic project-aware retrieval contract. When omitted, the tool MUST preserve shared-scope-only search. The tool MUST reject malformed or unknown project identifiers before lower-level retrieval and MUST retain bounded output, provenance, freshness, and diagnostics.

#### Scenario: Search through MCP with a project selector
- **WHEN** a client invokes `knowledge_search` with a valid project identifier and a bounded query
- **THEN** the facade returns merged shared/project results with source scope and virtual-project provenance

#### Scenario: Search through MCP without a project selector
- **WHEN** a client invokes `knowledge_search` without a project identifier
- **THEN** it returns shared-knowledge results using the existing result contract

#### Scenario: Reject an unknown project selector
- **WHEN** a client invokes `knowledge_search` with a malformed or unknown project identifier
- **THEN** the server returns a sanitized `INVALID_ARGUMENT` or `NOT_FOUND` result without exposing local paths

