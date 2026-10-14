## ADDED Requirements

### Requirement: Legacy runtime surfaces are absent
The packaged product MUST use the single-workspace HTTP launcher as its default executable service entry and MUST retain local stdio MCP only through its explicit launcher. Production source, resources, configuration, and active documentation MUST NOT expose the legacy `/api/v1` application, logical workspace registration/selection, multi-workspace registry/context lifecycle, or an automatic fallback to legacy storage.

#### Scenario: Start the packaged service
- **WHEN** an operator starts the executable jar without selecting another main class
- **THEN** the single-workspace HTTP service requires canonical `workspace_root` configuration and does not start the legacy CLI or `/api/v1` server

#### Scenario: Search active product surfaces
- **WHEN** repository validation inspects non-archived production code, resources, examples, and configuration
- **THEN** it finds no callable workspace registration/selection path or supported `/api/v1` route

### Requirement: Cleanup preserves production dependencies
Legacy code MUST be removed only when static reachability and repository-reference evidence show that it is not required by the single-workspace HTTP, WebUI, stdio MCP, document application, engineering projection, retrieval, trace, or Context Builder paths. An unresolved candidate MUST remain present until its dependency Gate is resolved.

#### Scenario: Candidate has a shared caller
- **WHEN** a deletion candidate is referenced by a retained production root or implements behavior used by `KnowledgeBaseDocumentApplication`
- **THEN** the candidate is retained or first replaced through a separately reviewed seam instead of being deleted as legacy code

#### Scenario: Candidate is legacy-only
- **WHEN** a candidate is reachable only from the removed legacy runtime, its exclusive tests, or obsolete documentation
- **THEN** it may be deleted together with those references without adding a compatibility shim

## MODIFIED Requirements

### Requirement: Production launchers share validated composition inputs
The HTTP and MCP production entry points MUST use one shared launcher factory to load canonical CLI/TOML configuration, construct the configured LLM client and immutable bounded external-tool profiles, and open `SingleWorkspaceKnowledgeService`. The packaged default executable MUST start the HTTP entry point; MCP MUST remain available through its explicit local stdio main class. CLI values SHALL override matching canonical TOML values, but any obsolete workspace key MUST remain fatal. External-tool executable paths MUST be explicit absolute paths; an unavailable optional tool MUST produce bounded unavailable diagnostics rather than an ambient `PATH` search. Transports MUST NOT duplicate these construction responsibilities or fall back to a legacy CLI/server.

#### Scenario: Start HTTP from the executable jar
- **WHEN** the operator starts the packaged jar with valid canonical configuration
- **THEN** it opens one ready single-workspace composition and serves `/api/v2` plus the bundled WebUI without exposing `/api/v1`

#### Scenario: Start MCP from canonical configuration
- **WHEN** the operator starts the explicit MCP main class with a valid workspace root, document LLM settings, and bounded tool profiles
- **THEN** the shared launcher factory returns one ready production composition before MCP registers tools

#### Scenario: Optional tool is not configured
- **WHEN** a valid document-only service configuration omits an optional external tool
- **THEN** application readiness remains available and dependent operations report the tool as unavailable without searching the process `PATH`
