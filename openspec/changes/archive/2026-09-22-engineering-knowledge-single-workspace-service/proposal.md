## Why

The engineering-knowledge modules are implemented but are not composed into a usable HTTP, WebUI, or MCP service. This is a new product implementation with no requirement to preserve legacy content or multi-workspace behavior, so the runtime should converge on one configured workspace and one production application composition.

## What Changes

- **BREAKING** Replace the multi-workspace runtime contract with exactly one workspace root owned by the service instance; remove logical workspace registration, switching, cross-workspace fallback, and `workspaceId` from the new service API.
- Compose document ingestion, engineering projection ingestion, controlled object reads, hybrid retrieval, relation trace, Context Builder, and the agent facade over the same workspace-owned SQLite/Lucene resources.
- Replace the MCP placeholder handler with the production `KnowledgeAgentFacade` composition while retaining local stdio-only, read-only, bounded behavior.
- Simplify the bundled WebUI to one workspace: remove workspace selection and lifecycle registration controls while retaining ingestion, search, ask, document, session, index, and status workflows that remain supported by the new service.
- Start with a fresh workspace layout and schema; do not migrate or import legacy databases, indexes, configuration, sessions, or content.
- Keep legacy multi-workspace implementation code outside the new startup path until the new service passes end-to-end verification; physical deletion is a separate cleanup decision.

## Capabilities

### New Capabilities

- `single-workspace-knowledge-service`: Defines the one-workspace runtime, application composition, startup/configuration, HTTP surface, fresh-state policy, health/readiness, and shared HTTP/MCP lifecycle.

### Modified Capabilities

- `workspace-service`: Replaces multi-workspace registration, isolation, routing, and lifecycle requirements with a single service-owned workspace contract.
- `multi-workspace-webui`: Replaces workspace selection, registration, and fallback behavior with a single-workspace UI contract.
- `engineering-knowledge-mcp-exposure`: Requires the stdio server to use the production facade composition rather than an unavailable placeholder, without adding a network transport.

## Impact

- Affected areas: CLI/server startup, HTTP composition and request contracts, workspace/resource lifecycle, bundled WebUI assets, MCP startup, and application wiring for ingestion/retrieval/context modules.
- Compatibility: breaking for multi-workspace configuration, workspace lifecycle endpoints, `workspaceId` request fields, and legacy persisted content. Compatibility shims and data migration are explicitly out of scope.
- Dependencies and deployment: no new dependency or remote service is planned; SQLite and Lucene remain host-local and owned by one service instance.
