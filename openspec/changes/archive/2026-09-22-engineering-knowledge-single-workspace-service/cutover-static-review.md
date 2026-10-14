## Work Item 5.2 static cutover review

Reviewed on 2026-09-22 against the production HTTP and MCP entry points, their
transport handlers, the shared launcher, the single-workspace composition root,
the bundled UI sources/resources, and `pom.xml`.

| Boundary | Static evidence | Result |
| --- | --- | --- |
| Transport construction | `SingleWorkspaceHttpServerMain` and `KnowledgeMcpServerMain` obtain `SingleWorkspaceKnowledgeService` only through `SingleWorkspaceLauncher`. HTTP imports application/runtime interfaces; MCP handlers import `KnowledgeAgentFacade` and validation/value types. No transport source imports or constructs `SqliteKnowledgeStore`, a Lucene projection/runtime, ripgrep, Ctags, Git adapters, `WorkspaceRegistry`, or `WorkspaceContext`. Concrete Store, retrieval, and tool-adapter construction remains in `SingleWorkspaceKnowledgeService`. | PASS |
| Workspace-free HTTP | `SingleWorkspaceHttpApp` mounts the v2 service surface and has no registration, unregistration, workspace inventory, or workspace-selection route. The obsolete query/body/form/header names are present only in the pre-application rejection guard. Response conversion recursively removes the internal `workspaceId`. | PASS |
| Workspace-free MCP and UI | The six MCP input/output schemas contain no `workspaceId`; the handler uses the internal facade DTO field and removes it before serialization. The bundled UI has no workspace selection/registration request helper and targets `/api/v2`. Remaining CSS/element names containing the ordinary word `workspace` are presentation names, not routing fields or lifecycle operations. | PASS |
| Fresh state | `SingleWorkspaceLauncher` validates canonical configuration and opens `SingleWorkspaceKnowledgeService`; the composition calls `SingleWorkspaceLayout.open` before constructing mutable Store, retrieval, document, or tool resources. The layout accepts only an absent/empty root or the exact format/version-1 marker and does not import, migrate, delete, or open an unmarked legacy layout. | PASS |
| MCP transport | `KnowledgeMcpServer` constructs only `StdioServerTransportProvider`; neither the production main nor server contains SSE, WebSocket, HTTP, or another remote MCP listener. | PASS |
| Excluded scope | No authentication/authorization layer was added. `pom.xml` has no change. The new transport/launcher paths add no deployment automation and no legacy/server-side data migration. Existing Store-owned compatible-schema initialization remains encapsulated in the composition and is not a transport migration facility. | PASS |

### Residual boundaries

- The internal constant workspace identity remains in existing application DTOs for repository confinement; both transports remove it from client contracts and serialized results.
- Legacy multi-workspace classes and `/api/v1` documentation/code still exist but are bypassed by the documented production entry points. Physical deletion remains a separate optional cleanup change after verification.
- HTTP and standalone stdio MCP are separate entry points. Operators must not point independent processes at the same local workspace; this restriction is documented and no distributed/shared-filesystem deployment behavior is introduced.
- This Work Item is static evidence only. Runtime HTTP/UI/MCP smoke verification remains Work Item 5.3.
