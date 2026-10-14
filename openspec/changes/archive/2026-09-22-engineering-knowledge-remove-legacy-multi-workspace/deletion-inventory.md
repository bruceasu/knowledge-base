# Legacy deletion inventory

Work Item: `0.1`

This inventory is a static reachability snapshot taken before deletion. `DELETE` means the
file is reachable only from the obsolete `/api/v1` multi-workspace product surface.
`KEEP_SHARED` means a supported production root or retained contract still reaches it.
`GATE` means deletion is forbidden until Work Item 0.2 records the replacement or confirms
equivalent retained coverage.

## Production roots and exclusions

Retained roots are:

- `SingleWorkspaceHttpServerMain` and `SingleWorkspaceHttpApp` for `/api/v2` and bundled WebUI;
- `KnowledgeMcpServerMain` for local stdio MCP;
- `SingleWorkspaceLauncher`, `SingleWorkspaceKnowledgeService`, and
  `KnowledgeBaseDocumentApplication` for the shared application graph;
- `src/main/resources/webui/**`, `config-example.toml`, and the Maven shaded artifact;
- the active OpenSpec main specs and the archived OpenSpec change history.

The following are not evidence of obsolete routing and remain out of deletion scope:

- internal engineering projection DTO fields named `workspaceId`;
- tests that assert obsolete configuration is rejected;
- document-domain storage, session, SQLite, Lucene, LLM, ingestion, retrieval, context, and
  trace code reachable from the retained application graph;
- presentation-only CSS class names and archived OpenSpec artifacts.

## Production source matrix

| File | Classification | Reference evidence / prerequisite | Planned batch |
| --- | --- | --- | --- |
| `src/main/java/me/asu/kb/http/KnowledgeBaseHttpApp.java` | DELETE (done) | Removed after the production launcher received an immutable LLM settings seam and the old CLI server command was detached. | 2.1 |
| `src/main/java/me/asu/kb/http/KnowledgeBaseHttpService.java` | DELETE (done) | Removed with its exclusive `/api/v1` application closure. | 2.1 |
| `src/main/java/me/asu/kb/http/WorkspaceRegistry.java` | DELETE (done) | Removed with the legacy service; no retained production caller existed. | 2.1 |
| `src/main/java/me/asu/kb/http/WorkspaceContext.java` | DELETE (done) | Removed with the legacy registry; retained lifecycle coordination uses `ServiceOperationCoordinator`. | 2.1 |
| `src/main/java/me/asu/kb/eventbus/KnowledgeBaseEventBusBridge.java` | DELETE (done) | Removed because its sole production caller and service type were deleted. | 2.1 |
| `src/main/java/me/asu/kb/cli/KnowledgeBaseCli.java` | DELETE (done) | Removed after the manifest and supported launch documentation had switched to `SingleWorkspaceHttpServerMain`; its remaining commands called only the obsolete `/api/v1` client. | 3.1 |
| `src/main/java/me/asu/kb/cli/KnowledgeBaseHttpClient.java` | DELETE (done) | Removed with its sole production caller, `KnowledgeBaseCli`; it exposed only `/api/v1` operations. | 3.1 |
| `src/main/java/me/asu/kb/cli/KnowledgeBaseConfig.java` | DELETE (done) | Removed after the retained launcher adopted its immutable nested `LlmProviderSettings` replacement in 2.1. | 3.1 |
| `src/main/java/me/asu/util/TomlConfigLoader.java` | DELETE (done) | Removed with its sole caller, `KnowledgeBaseConfig`; canonical TOML parsing remains owned by the supported launcher. | 3.1 |
| `src/main/java/me/asu/util/EnvLoader.java` | DELETE (done) | The 4.1 rescan confirmed no caller remained after `KnowledgeBaseConfig` was deleted; the standalone legacy `.env` discovery helper was removed. | 4.1 |
| `src/main/java/me/asu/kb/http/SingleWorkspaceHttpApp.java` | KEEP_SHARED | Direct production `/api/v2` HTTP/WebUI adapter. | Retain |
| `src/main/java/me/asu/kb/http/SingleWorkspaceHttpServerMain.java` | KEEP_SHARED | Supported HTTP process root and future manifest main. | Retain |
| `src/main/java/me/asu/kb/runtime/SingleWorkspaceLauncher.java` | KEEP_SHARED | Shared HTTP/MCP composition and direct caller of the retained LLM configuration seam. | Retain |
| `src/main/java/me/asu/kb/runtime/SingleWorkspaceKnowledgeService.java` | KEEP_SHARED | Shared service-owned application graph. | Retain |
| `src/main/java/me/asu/kb/runtime/KnowledgeBaseDocumentApplication.java` | KEEP_SHARED | Retained adapter over document/session domain behavior. | Retain |
| `src/main/java/me/asu/kb/service/**`, `src/main/java/me/asu/kb/session/**` | KEEP_SHARED | Reached through `KnowledgeBaseDocumentApplication`; package age does not make these legacy. | Retain |
| `src/main/java/me/asu/kb/llm/**` | KEEP_SHARED | Reached through `SingleWorkspaceLauncher` and `LlmClientFactory`. | Retain |
| engineering adapters, store, retrieval, context, read, trace, agent, ingestion and MCP packages | KEEP_SHARED | Reached through `SingleWorkspaceKnowledgeService` and `KnowledgeMcpServerMain`. | Retain |

## Tests and fixtures matrix

| File | Classification | Reference evidence / coverage decision | Planned batch |
| --- | --- | --- | --- |
| `src/test/java/me/asu/kb/http/WorkspaceContextTest.java` | DELETED | Tested only the removed registry/context multi-workspace lifecycle. Retained shutdown/deadline behavior remains covered by `ServiceOperationCoordinatorTest`. | Completed in 2.2 |
| `src/test/java/me/asu/kb/cli/KnowledgeBaseCliTest.java` | DELETED | Removed after its `/api/v1` cases and production subject were gone. Its final provider-option parser assertion is covered at the supported configuration boundary by `SingleWorkspaceLauncherTest`, with provider construction retained in `LlmClientFactoryTest`. | Completed in 3.2 |
| `src/test/java/me/asu/kb/http/KnowledgeBaseSessionHttpTest.java` | DELETED | Replaced by real-production `/api/v2` create/list/message/summary/export/import coverage in `SingleWorkspaceHttpIntegrationTest`. Repository round-trip and Ask/session behavior remain covered by `KnowledgeRepositorySessionTest` and `KnowledgeBaseSessionTest`. | Completed in 2.2 |
| `src/test/java/me/asu/kb/http/KnowledgeBaseHttpAppTest.java` | DELETED | Obsolete workspace registration, old config, `/api/v1`, and old UI assertions were removed. `SingleWorkspaceHttpIntegrationTest` now covers `/api/v2` document create/view/conflict/metadata/history and bounded listing; domain tests retain revision, metadata, trash, sync, and search semantics. | Completed in 2.2 |
| `src/test/java/me/asu/kb/http/SingleWorkspaceHttpContractTest.java` | KEEP_SHARED | Enforces removal of workspace routing from the supported contract. | Retain |
| `src/test/java/me/asu/kb/http/SingleWorkspaceHttpIntegrationTest.java` | KEEP_SHARED | Exercises the fresh `/api/v2` service graph, obsolete-key rejection, and deterministic shutdown. | Retain |
| `src/test/java/me/asu/kb/http/SingleWorkspaceWebUiContractTest.java` | KEEP_SHARED | Protects bundled/source parity and workspace-free browser endpoints. | Retain |
| `src/test/java/me/asu/kb/runtime/**` | KEEP_SHARED | Covers layout, launcher, composition, document/session application behavior, deadlines, and shutdown. | Retain |

## Packaging, resources, configuration, and active documentation

| File | Classification | Reference evidence / prerequisite | Planned batch |
| --- | --- | --- | --- |
| `pom.xml` | KEEP_SHARED (updated) | Authoritative build file now names `SingleWorkspaceHttpServerMain` as the manifest main and disables creation of the derived reduced POM. | 1.2 |
| `dependency-reduced-pom.xml` | DELETE (done) | Removed the tracked Maven Shade output; `createDependencyReducedPom=false` prevents regeneration. | 1.2 |
| `knowlege-base.toml.example` | DELETE (done) | Removed the misspelled legacy example containing obsolete server/WebUI/EventBus configuration. `config-example.toml` remains the supported single-workspace example. | 1.1 |
| `config-example.toml` | KEEP_SHARED | Canonical single-workspace configuration referenced by current README files. | Retain |
| `src/main/resources/webui/**` and `webui/**` | KEEP_SHARED | Served by `SingleWorkspaceHttpApp`; parity is asserted by `SingleWorkspaceWebUiContractTest`. Already-removed `workspace.js` copies remain deleted. | Retain |
| `README.md`, `README.zh.md` | KEEP_SHARED (updated) | Both languages now use `java -jar` for HTTP, retain the explicit MCP main, and describe legacy surfaces as unsupported cleanup targets. | 1.2 |
| `docs/single-workspace-service.md` | KEEP_SHARED (updated) | Supported service guide now identifies the single-workspace runtime as the only product path without claiming the old code remains available. | 1.1 |
| `docs/article-editing.md` | KEEP_SHARED (updated) | Retained domain behavior is documented using `/api/v2`, the fixed service workspace, bounded browsing, and obsolete-field rejection. | 1.1 |
| `docs/performance-measurement.md` | KEEP_SHARED (updated) | Measurement scenarios now use fresh temporary single-workspace roots and `/api/v2`; no logical workspace selection remains. | 1.1 |
| `docs/adr-workspace-server-concurrency.md` | DELETE (done) | Removed the active ADR for the obsolete multi-workspace `WorkspaceContext` model. Git preserves history. | 1.1 |
| `docs/agents/workspace-concurrency-glossary.md` | DELETE (done) | Removed agent vocabulary that described only the obsolete registry/context lifecycle. | 1.1 |
| `docs/compatibility-acceptance.md` | DELETE (done) | Removed the acceptance record for old CLI, `/api/v1`, and multi-workspace compatibility. | 1.1 |
| `docs/management-page-acceptance.md` | DELETE (done) | Removed the manual checklist for `/api/v1` and workspace switching. | 1.1 |
| `docs/proposals/workspace-server-concurrency.md` | DELETE (done) | Removed the discussion-stage superseded proposal; OpenSpec archive remains intact. | 1.1 |
| `openspec/changes/archive/**` | KEEP_SHARED | Explicitly preserved lifecycle history; excluded from active legacy-token assertions. | Retain |

## Specification decision

`openspec/specs/document-metadata-search/spec.md` still requires repeatable
`--server-root <workspaceId>=<path>` declarations, `server_roots`, a special legacy unpaged
response, and management-page workspace switching. The change now includes a delta for that
capability: root lifecycle remains inside the one service workspace, browsing always uses a
bounded workspace-free response, and the UI has no workspace selector.

## Gate resolutions

1. **Configuration seam — resolved:** introduce an immutable single-workspace LLM settings
   implementation, then delete `KnowledgeBaseConfig` and `TomlConfigLoader`. Recheck
   `EnvLoader` as an orphan in 4.1.
2. **Legacy-test coverage — resolved:** add v2 document/session transport coverage before
   deleting both legacy HTTP tests; rely on the named domain tests for deeper persistence rules.
3. **Active documentation — resolved:** rewrite article/performance guides; delete the obsolete
   ADR, glossary, acceptance documents, and discussion proposal; preserve OpenSpec archives.
4. **Active specification — resolved:** the new `document-metadata-search` delta removes the
   contradictory logical-workspace and legacy-response requirements.

No `GATE` classification remains. The 4.1 reference rescan is a deletion-time safety check, not
an unresolved design decision.

## Final keep/delete closeout

The completed matrix accounts for 21 deleted tracked files: 10 production Java files, four
legacy-only test files, and seven obsolete documentation, configuration-example, packaging-output,
or proposal files. The retained side remains rooted at the two launchers, the shared runtime and
document application, `/api/v2` plus bundled WebUI resources, the six-tool stdio MCP facade, and
the service-owned Store/projection/retrieval/trace/context graph described below.

| Final class | Count / scope | Closeout result |
| --- | --- | --- |
| `DELETE` | 21 tracked files | All matrix-approved legacy-only files are deleted; Git history remains recoverable. |
| `KEEP_SHARED` | Named production roots, shared packages, WebUI/config resources, active specs/docs, and focused tests | Retained and statically reachable from a supported root. |
| `GATE` | 0 | All four original Gates were resolved before deletion. |

No `%%` uncertainty note remains. Static diff inspection found no user workspace data, schema,
dependency-version, authentication, remote-transport, deployment-topology, or archived OpenSpec
history change. Focused compile and runtime evidence is intentionally separate and remains pending
explicit authorization in Work Item 5.2.

## Post-deletion retained graph

The 4.1 orphan sweep intentionally retains these shared components and production roots:

- `/api/v2` and bundled WebUI: `SingleWorkspaceHttpServerMain` -> `SingleWorkspaceHttpApp` ->
  `SingleWorkspaceLauncher`;
- local stdio MCP: `KnowledgeMcpServerMain` -> `SingleWorkspaceLauncher`;
- document and session behavior: `SingleWorkspaceKnowledgeService` ->
  `KnowledgeBaseDocumentApplication` -> `KnowledgeBase` and session repositories;
- LLM construction: `SingleWorkspaceLauncher` -> `LlmClientFactory` and `me.asu.kb.llm/**`;
- engineering ingestion, Store, retrieval, context, trace, read, and adapter packages:
  `SingleWorkspaceKnowledgeService` and the MCP tool registry;
- bundled/source WebUI copies, `config-example.toml`, and Maven packaging: the supported HTTP
  launcher and its active documentation/tests.

Old option names are retained only by `SingleWorkspaceServiceConfig` as fatal-input guards and by
tests that assert those guards. They are not callable workspace-selection behavior.

## Frozen implementation order

1. `1.1`: active documentation/configuration cleanup.
2. `1.2`: manifest and README launch contract.
3. `2.1`: retained LLM settings seam, then legacy HTTP/service/registry/context/EventBus closure.
4. `2.2`: replacement v2 boundary tests, then legacy HTTP tests.
5. `3.1` and `3.2`: legacy CLI/client/configuration closure and tests.
6. `4.1` and `4.2`: orphan rescan and static contract enforcement.

Rollback is source-only per batch. No batch modifies user data, workspace markers, database or
index schemas, dependencies, authentication, deployment topology, or archived history.

No production source, resource, test, user data, or archived history is deleted by Work Item
0.1.
