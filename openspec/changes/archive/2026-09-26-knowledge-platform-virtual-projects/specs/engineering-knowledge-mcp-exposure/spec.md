## ADDED Requirements

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
