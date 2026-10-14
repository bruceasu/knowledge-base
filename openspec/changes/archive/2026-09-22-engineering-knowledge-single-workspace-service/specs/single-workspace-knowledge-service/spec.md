## ADDED Requirements

### Requirement: Service owns exactly one workspace
The system SHALL start with exactly one local workspace root supplied by `--workspace-root` or TOML `workspace_root` and SHALL own that workspace's SQLite, Lucene, ingestion, retrieval, context, HTTP, and MCP resources for the process lifetime. The new launcher MUST NOT use an implicit workspace default. Clients MUST NOT register, select, switch, or name a logical workspace.

#### Scenario: Start an empty service workspace
- **WHEN** the operator starts the service with one valid empty workspace root
- **THEN** the service initializes one workspace resource graph and exposes no workspace registration or selection operation

#### Scenario: Reject ambiguous workspace configuration
- **WHEN** configuration supplies zero roots, multiple roots, or an obsolete multi-workspace mapping
- **THEN** startup fails before opening mutable resources with an actionable configuration error

### Requirement: New-format state is explicit and legacy state is unsupported
The service MUST create and validate `<workspace-root>/.engineering-knowledge-workspace.json` with format `engineering-knowledge-single-workspace` and integer version `1`. It MAY initialize an absent or empty root and MUST publish the marker atomically before opening mutable Store or index resources. It MUST NOT import, migrate, delete, or silently open a legacy or unrecognized database, index, configuration, session, or content layout.

#### Scenario: Encounter legacy state
- **WHEN** the configured root contains legacy or unrecognized persisted state without a compatible new-format marker
- **THEN** startup fails without mutating that state and explains that a fresh workspace is required

#### Scenario: Encounter an incompatible marker
- **WHEN** the marker is malformed, names another format, contains additional fields, or has a version other than integer `1`
- **THEN** startup fails before opening SQLite or Lucene and leaves the workspace unchanged

### Requirement: One production application composition serves both transports
The system SHALL compose the fresh-layout document module, projection ingestion, hybrid retrieval, controlled object read, evidence trace, Context Builder, and `KnowledgeAgentFacade` over one workspace-owned resource graph. The composition root MUST own one engineering Store, one retrieval runtime owning engineering Lucene, one application operation coordinator, and immutable bounded external-tool profiles. HTTP and MCP handlers MUST call application interfaces and MUST NOT construct or access persistence, index, process launcher, or external-tool adapters directly.

#### Scenario: HTTP and MCP observe the same committed state
- **WHEN** content is successfully ingested through a supported application operation and then queried through HTTP and MCP
- **THEN** both transports read the same committed workspace state and preserve the same provenance and freshness semantics

#### Scenario: Both transports are enabled
- **WHEN** HTTP and MCP run in one service process
- **THEN** they receive interfaces from the same composition-root instance and do not open duplicate Store, Lucene, document, or external-tool resource graphs

### Requirement: Production document operations use the fresh document subdirectory
The system MUST provide a production `SingleWorkspaceDocumentApplication` that owns exactly one existing document-service instance opened under `<workspace-root>/document`. It MUST implement every declared document/session operation through typed application methods, MUST reject malformed or root-escaping arguments, and MUST NOT use `WorkspaceRegistry`, `WorkspaceContext`, legacy transport handlers, or a legacy root layout. A placeholder or close-only implementation MUST NOT satisfy application readiness.

#### Scenario: Execute a document operation
- **WHEN** a supported document or session operation is accepted by HTTP or another application caller
- **THEN** the production adapter executes it against the one fresh document subdirectory and returns its application result without performing workspace selection

#### Scenario: Reject an escaping document path
- **WHEN** an operation supplies a relative or absolute path that escapes the service workspace where a confined path is required
- **THEN** the adapter rejects it before the document service mutates state

### Requirement: Production launchers share validated composition inputs
HTTP and MCP production entry points MUST use one shared launcher factory to load canonical CLI/TOML configuration, construct the configured LLM client and immutable bounded external-tool profiles, and open `SingleWorkspaceKnowledgeService`. CLI values SHALL override matching canonical TOML values, but any obsolete workspace key MUST remain fatal. External-tool executable paths MUST be explicit absolute paths; an unavailable optional tool MUST produce bounded unavailable diagnostics rather than an ambient `PATH` search. Transports MUST NOT duplicate these construction responsibilities.

#### Scenario: Start MCP from canonical configuration
- **WHEN** the operator supplies a valid workspace root, document LLM settings, and bounded tool profiles
- **THEN** the shared launcher factory returns one ready production composition before MCP registers tools

#### Scenario: Optional tool is not configured
- **WHEN** a valid document-only service configuration omits an optional external tool
- **THEN** application readiness remains available and dependent operations report the tool as unavailable without searching the process `PATH`

### Requirement: Package-internal retrieval and context details stay encapsulated
The retrieval package MUST provide an `AutoCloseable` runtime factory that owns its Lucene projection and exposes application-level retrieval, recovery/rebuild, and status seams. The context package MUST provide a factory returning `ContextBuilder` from public application dependencies. Candidate sources, Lucene implementation types, and the context relation-reader adapter MUST remain inaccessible to transports.

#### Scenario: Construct the production root
- **WHEN** the composition root wires retrieval and context building
- **THEN** it uses their public factories and neither HTTP nor MCP imports or constructs package-internal implementation types

### Requirement: Readiness and shutdown cover the complete composition
The service MUST expose `/api/v2/health` for process liveness and `/api/v2/ready` for composition readiness. Readiness MUST return HTTP 200 only after the workspace store, projection/index recovery, and required application composition are usable, and HTTP 503 otherwise. Its stable payload MUST always contain `schemaVersion`, `ready`, `state`, `components`, `queueDepth`, and `freshness`, MUST use only bounded operator-safe failure reasons, and MUST NOT disclose the absolute workspace root. Shutdown MUST reject new operations, terminate or bound accepted operations, and close transport and application resources in a deterministic order.

#### Scenario: Store cannot open
- **WHEN** SQLite initialization or compatible-layout validation fails
- **THEN** readiness remains unavailable and neither HTTP nor MCP advertises usable knowledge operations

#### Scenario: Shut down an accepting service
- **WHEN** shutdown begins while bounded operations are accepted
- **THEN** readiness becomes false, transports stop accepting work, accepted work drains or is interrupted by the deadline, and document, Lucene, then Store resources close once in dependency order

#### Scenario: An optional external tool is unavailable
- **WHEN** ripgrep, Ctags, or Git cannot be invoked but Store, index, and application composition are usable
- **THEN** document-only readiness remains available and dependent operations return explicit bounded incomplete or unavailable diagnostics

### Requirement: Breaking HTTP contract is workspace-free
The new HTTP contract MUST be mounted under `/api/v2` without aliasing or redirecting `/api/v1`, and MUST expose supported status, ingestion, retrieval, ask, document/session, index/root, trace, and context operations without a workspace routing field. It MUST reject `workspaceId`, `workspace`, or `workspace_id` in query, JSON, or multipart input, and `X-Workspace-Id` or `X-Workspace` headers, before invoking application logic. Rejection MUST use HTTP 400 and code `OBSOLETE_WORKSPACE_FIELD`. It MUST NOT perform cross-workspace fallback or expose the internal constant workspace identity.

#### Scenario: Client sends obsolete workspace routing
- **WHEN** a request to the new API contains `workspaceId` or another workspace-selection field
- **THEN** the service returns HTTP 400 with code `OBSOLETE_WORKSPACE_FIELD` instead of ignoring or routing by that field

### Requirement: Obsolete multi-workspace configuration fails closed
The new launcher MUST reject legacy CLI keys `--workspace`, `--workspace-id`, `--server-workspace`, and `--server-root`, and legacy TOML keys `workspace`, `workspace_id`, `workspaces`, `server_workspace`, `server_root`, and `server_roots`. It MUST name the obsolete key and direct the operator to `--workspace-root` or `workspace_root`, even when a canonical root is also supplied.

#### Scenario: Canonical and obsolete roots are both configured
- **WHEN** startup receives `--workspace-root` together with `--server-workspace`
- **THEN** validation fails before marker creation or mutable resource construction and identifies `--server-workspace` as obsolete

### Requirement: Service verification uses fresh temporary state
End-to-end verification MUST start from a temporary empty new-format workspace and cover startup/readiness, one ingestion flow, retrieval, controlled read, context construction, UI asset loading, and all six MCP tools. Verification MUST NOT depend on legacy persisted fixtures.

#### Scenario: Complete service smoke flow
- **WHEN** the authorized smoke suite runs against a temporary workspace
- **THEN** it demonstrates usable HTTP, bundled UI, and MCP flows and leaves the repository's real workspace data untouched
