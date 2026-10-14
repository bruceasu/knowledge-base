# Current Service and Composition Inventory

This inventory is the evidence for Work Item 0.1. It records the current seams without selecting the replacement HTTP version, configuration keys, state marker, or close order; those decisions belong to Work Items 0.2 and 0.3.

## Launchers and configuration

| Area | Current entry point / fields | Classification for the new service |
| --- | --- | --- |
| CLI | `KnowledgeBaseCli.main`; `serve` and `http` call `startHttpServer`, then `KnowledgeBaseHttpApp.start()` and `syncDeclaredRoots(...)`. Client commands send `workspaceId`. | Reuse command dispatch and shutdown-hook pattern. Replace the server construction and workspace-bearing client contract. |
| Direct HTTP | `KnowledgeBaseHttpApp.main` loads `KnowledgeBaseConfig`, constructs the map-based app, starts it, and blocks the main thread. | Reuse the process lifecycle and static-asset hosting shape. The selected constructor and service are legacy-only. |
| Configuration | `KnowledgeBaseConfig` exposes `workspace`, `workspaceId`, `workspaces`, `serverRoots`, `port`, `serverUrl`, `serverTimeoutSeconds`, LLM settings, `webuiDir`, and event-bus settings. `configuredWorkspaces()` always inserts `default` and then the configured map. Root declarations use `workspaceId=path`. | LLM, port, timeout, WebUI, and event-bus settings are reusable candidates. `workspaceId`, `workspaces`, `server-workspace`, and ID-qualified `server-root` are legacy-only. Work Item 0.2 must define the exact one-root contract and obsolete-field rejection. |

## Legacy HTTP ownership

`KnowledgeBaseHttpApp` constructs `KnowledgeBaseHttpService`, registers the `/api/v1` routes, owns the HTTP `Application` and optional event-bus executor, and closes the service before shutting down the application.

`KnowledgeBaseHttpService` owns one `WorkspaceRegistry`, keeps the selected ID in a `ThreadLocal`, opens a context per request through `withWorkspace(...)`, exposes registration/unregistration/repair, and emits workspace inventory and `workspaceId` fields. It is coupled to the legacy `me.asu.kb.service.KnowledgeBase` API.

`WorkspaceRegistry` owns the ID-to-root registrations and lazily owns one `WorkspaceContext` per ID. `WorkspaceContext` constructs and closes the legacy `KnowledgeBase`, owns the bounded single-writer executor, request timeout, retry state, and read/write lifecycle locks.

Therefore `WorkspaceRegistry`, its registration lifecycle, `ThreadLocal` routing, and the current `WorkspaceContext` composition are legacy-only paths. The bounded-operation and lifecycle ideas may be reused, but these classes are not the new production composition root.

## Bundled WebUI request inventory

The editable WebUI lives under `webui/`; a second bundled copy under `src/main/resources/webui/` is served from the jar. Both copies currently contain the workspace behavior and must remain synchronized when changed.

- `workspace.js` persists a selected workspace and appends `workspaceId` to URLs.
- `app.js` targets `/api/v1`, stores `state.workspaceId`, adds `workspaceId` to bodies/URLs, switches sessions with the selected workspace, performs cross-workspace zero-hit fallback, and includes workspace identity in references.
- `manage.js` renders workspace inventory and selectors, registers workspaces, and sends the selected ID for upload, ingestion, rebuild, roots, documents, metadata, and cleanup operations.
- The related HTML pages contain workspace selectors, registration controls, and load `workspace.js`.

Useful single-workspace flows are search, ask, sessions, upload/file/directory ingestion, documents and revisions, trash, roots, index maintenance, cleanup, status, and bundled asset loading. Workspace selection, registration, repair, unregistration, inventory, saved selection, and fallback are legacy-only.

## MCP startup

`KnowledgeMcpServerMain` is a standalone stdio entry point and is not invoked by the current CLI or HTTP launchers. It starts `KnowledgeMcpServer` with `System.in`/`System.out`, installs a shutdown hook, and blocks on a latch. Its handler always returns the error `tool handler is not configured`.

The stdio server lifecycle and handler injection seam are reusable. The placeholder handler is legacy scaffolding and must be replaced by the production facade composition in Work Item 4.1.

## Engineering-knowledge construction seams

| Component | Existing seam | Reuse finding |
| --- | --- | --- |
| Store | `new SqliteKnowledgeStore(Path dbFile)` opens SQLite and applies its repeat-safe forward migration; the Store is `AutoCloseable`. | Directly reusable under one composition owner. The state marker must be validated before this constructor can mutate/open state. |
| Projection ingestion | `new SqliteKnowledgeProjectionIngestion(store, Map<String, Path> workspaceRoots)` | Reusable with a singleton internal ID-to-root map. Transport code must not build it. |
| Controlled read | `new SqliteKnowledgeObjectReader(internalWorkspaceId, store)` | Directly reusable with the constant internal workspace identity. |
| Relation trace | `new SqliteKnowledgeRelationTracer(internalWorkspaceId, store)` | Directly reusable with the same Store and internal identity. |
| Hybrid retrieval | `HybridKnowledgeRetriever(...)` takes a validator, one-to-four candidate sources, and Git evidence source, but the concrete class and constructor are package-visible. Candidate-source and Lucene setup are also retrieval-package details. | No public production factory exists. Work Item 0.3 must add one application-facing factory/composition seam rather than expose internals or construct them in HTTP/MCP. |
| Context Builder | `DefaultContextBuilder(retriever, relationReader)` exists, but the concrete class and constructor are package-visible. | No public production factory exists. Work Item 0.3 must provide a public factory/interface-returning seam. |
| Agent facade | `new DefaultKnowledgeAgentFacade(retriever, objectReader, codeSearcher, relationTracer, contextBuilder, sanitizer)` | Public reusable final assembly seam once its dependencies are supplied by the composition root. |

The engineering interfaces still carry `workspaceId` internally. The accepted design permits one constant internal identity; removing it from transport schemas does not require weakening repository confinement or rewriting all application interfaces.

## Reusable boundary and legacy-only summary

Reusable seams:

- CLI process dispatch, shutdown-hook shape, HTTP static-resource delivery, and MCP stdio handler injection.
- The engineering Store, projection ingestion, controlled read, relation trace, facade, adapters, and their application interfaces.
- One internal workspace identity and singleton catalog/map for existing confinement-aware application contracts.
- The useful single-workspace UI workflows and the source-to-bundled asset layout.

Legacy-only paths:

- `WorkspaceRegistry`, caller-selected `WorkspaceContext`, `ThreadLocal` workspace routing, and registration/repair/unregistration APIs.
- `configuredWorkspaces()`, `workspaceId`, workspace maps, ID-qualified roots, cross-workspace status/inventory, and `/api/v1` handlers that depend on them.
- WebUI selection persistence, selectors, registration controls, workspace-qualified requests/references, and fallback search.
- The MCP unavailable placeholder.

## Gates carried forward

Resolved by Work Items 0.2 and 2.1-2.2: `/api/v2`, the canonical one-root keys, marker/version, readiness payload, and obsolete-field errors were fixed before the production launcher and request boundary were completed.

Resolved by Work Items 0.3 and 1.3-1.4: public retrieval and Context Builder factories, the single resource graph, and deterministic close order are implemented; the static cutover review confirms that transports do not bypass them.

Resolved by Work Items 1.2-1.3: `SingleWorkspaceLayout.open` validates or atomically creates the marker before the composition constructs the Store or any other mutable resource.
