## Why

The verified single-workspace service is now the supported runtime, but the repository still contains the bypassed `/api/v1` server, multi-workspace registry/context lifecycle, old HTTP client and CLI entry behavior, dedicated tests, and legacy documentation. Keeping code that cannot be used by the new product increases maintenance cost, obscures the real entry points, and makes accidental reactivation of unsupported state possible.

## What Changes

- **BREAKING** Remove the legacy `/api/v1` HTTP application/service and multi-workspace `WorkspaceRegistry`/`WorkspaceContext` runtime.
- **BREAKING** Remove obsolete CLI/client commands and configuration paths that register, select, or route by logical workspace ID; make the supported single-workspace launchers the only packaged service entry paths.
- Delete tests, fixtures, WebUI remnants, configuration examples, and documentation that exist only for the removed runtime.
- Retain document-domain, SQLite, Lucene, LLM, ingestion, session, and other implementation classes that are still reachable from `KnowledgeBaseDocumentApplication` or the production single-workspace composition.
- Prove every deletion with production-root reachability and repository-reference evidence before removing it; uncertain shared code remains until a later bounded decision.
- Keep legacy persisted data unsupported and do not add migration, compatibility shims, dependencies, auth, remote transport, or deployment behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `single-workspace-knowledge-service`: Make the single-workspace HTTP and local stdio MCP launchers the sole supported packaged service paths and prohibit retained legacy transport/runtime fallbacks.
- `workspace-service`: Remove the remaining requirements for logical workspace registration, per-workspace routing/queues/status, legacy CLI service discovery, and other behavior belonging only to the deleted multi-workspace runtime.
- `document-metadata-search`: Retain document/root management for the one service workspace while removing logical-workspace startup declarations, legacy unpaged-client compatibility, and workspace-switching UI requirements.

## Impact

- Affected code: legacy classes under `me.asu.kb.http` and `me.asu.kb.cli`, their exclusive DTO/configuration paths, obsolete WebUI remnants, tests, scripts, Maven manifest/entry configuration, and legacy README material.
- Preserved code: the production `me.asu.kb.runtime` composition, `SingleWorkspaceHttpApp`, `KnowledgeMcpServerMain`, document-domain services used by `KnowledgeBaseDocumentApplication`, engineering Store/retrieval/ingestion/context modules, and their focused tests.
- Compatibility: intentionally breaking for old `/api/v1`, logical workspace IDs, legacy CLI commands, and old persisted layouts. No data migration is introduced.
- Dependencies and deployment: no new dependency, network transport, authentication, schema migration, or deployment topology is planned.
