## MODIFIED Requirements

### Requirement: Runtime dependency and lifecycle gate
The MCP server MUST use the approved official Java SDK runtime and MUST be constructed from the production single-workspace application composition. Startup MUST register the six tool handlers backed by `KnowledgeAgentFacade`; it MUST fail readiness rather than serving an unavailable placeholder handler. MCP lifecycle MUST remain independently disableable and MUST NOT change HTTP/CLI startup unless explicitly enabled by the new service configuration.

#### Scenario: Start production stdio MCP
- **WHEN** the operator enables MCP for a valid new-format service workspace
- **THEN** the server registers all six facade-backed read-only tools over stdio and no handler returns a configuration placeholder

#### Scenario: Composition cannot initialize
- **WHEN** the workspace or application composition cannot become ready
- **THEN** MCP startup fails without binding a network listener or advertising usable tools

## ADDED Requirements

### Requirement: MCP uses the implicit service workspace
MCP tool inputs MUST NOT accept `workspaceId`. Every tool SHALL operate on the one workspace owned by its service process while preserving path confinement, bounded output, provenance, freshness, and sanitization requirements.

#### Scenario: Invoke knowledge search
- **WHEN** a client invokes `knowledge_search` with a valid workspace-free request
- **THEN** the facade searches the service workspace and returns the existing versioned result contract

