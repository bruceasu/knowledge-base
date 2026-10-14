## 0. Contract and composition inventory

- [x] 0.1 Inventory the current CLI/HTTP launchers, configuration fields, `WorkspaceRegistry`/`WorkspaceContext` ownership, bundled UI requests, MCP placeholder startup, and constructors for Store, ingestion, retrieval, trace, Context Builder, and facade; record the exact reusable seams and legacy-only paths. Evidence: `composition-inventory.md`.
- [x] 0.2 Decide and document the breaking HTTP path/version, one-root CLI/config keys, new-format workspace marker/version, readiness payload, and obsolete-field rejection rules before changing a launcher or public request contract. Evidence: `contract-decisions.md`, `design.md`, and the `single-workspace-knowledge-service` delta spec.
- [x] 0.3 Define the production composition graph and deterministic close order, including which component owns SQLite migrations/connections, Lucene resources, external-tool profiles, executors, HTTP, and MCP; resolve any missing factory seam as a Gate rather than duplicating infrastructure access in transports. Evidence: `composition-design.md`, `design.md`, and the `single-workspace-knowledge-service` delta spec.

## 1. Single-workspace runtime

- [x] 1.1 Add immutable single-workspace configuration and descriptor/catalog values that normalize one root, expose only the internal identity required by application Interfaces, and reject zero, multiple, legacy-map, or root-escaping configuration before resource creation. Evidence: `SingleWorkspaceServiceConfig` and its focused unit tests.
- [x] 1.2 Add the versioned new-format workspace marker and initialization/open policy; verify that empty state initializes repeatably and legacy, missing-marker non-empty, or incompatible-version state fails without mutation. Evidence: `SingleWorkspaceLayout` and its focused unit tests.
- [x] 1.3 Implement one `AutoCloseable` production composition root wiring the concrete Store, document/code/Git adapters, projection ingestion, hybrid retriever, controlled read, relation trace, Context Builder, workspace catalog, and `DefaultKnowledgeAgentFacade` over one resource graph. Evidence: `SingleWorkspaceKnowledgeService`, `RetrievalRuntimeFactory`, and `ContextBuilderFactory`.
- [x] 1.4 Implement service readiness and shutdown coordination so failed initialization never advertises readiness, new work is rejected during close, bounded accepted work is handled according to its contract, and resources close once in the documented order.
- [x] 1.5 Implement the production `SingleWorkspaceDocumentApplication` adapter and shared launcher configuration/factory: open one existing `KnowledgeBase` only under the fresh `document` subdirectory, map every declared document/session operation without a placeholder, construct the configured LLM client and bounded absolute-path external-tool profiles, reject obsolete/ambiguous configuration before mutation, and expose one ready `SingleWorkspaceKnowledgeService` composition to HTTP and MCP entry points. Evidence: `KnowledgeBaseDocumentApplication`, `SingleWorkspaceLauncher`, focused adapter/configuration tests, and static construction/close-path review.

## 2. Workspace-free HTTP service

- [x] 2.1 Add the selected breaking HTTP entry point over the production composition, removing workspace registration/unregistration/repair routes and translating supported status, ingestion, retrieval, trace, context, document/session, root/index, and maintenance operations to application Interfaces.
- [x] 2.2 Remove `workspaceId` routing and cross-workspace fallback from the new request contract; reject obsolete workspace fields consistently and preserve stable validation, outcome, provenance, freshness, and incomplete diagnostics.
- [x] 2.3 Add focused HTTP tests using a temporary fresh workspace for startup/readiness, obsolete configuration/request rejection, ingestion-to-retrieval, controlled read, trace/context, failure mapping, and deterministic shutdown.

## 3. Single-workspace bundled UI

- [x] 3.1 Remove workspace selection, saved workspace state, registration/unregistration/repair controls, workspace inventory rendering, and fallback-search loops from the bundled use and management pages.
- [x] 3.2 Retarget supported search, ask, session, ingestion, document/edit/history, root/index, maintenance, status, and reference-opening requests to the workspace-free HTTP contract while retaining stale-response protection for in-flight UI operations.
- [x] 3.3 Keep source and generated WebUI assets synchronized and add focused static/browser-harness assertions that no request emits `workspaceId`, all retained flows use the new endpoints, and bundled assets load from the service jar.

## 4. Production MCP composition

- [x] 4.1 Replace the placeholder MCP handler with configuration loading and the shared production composition root; register all six existing `KnowledgeAgentFacade` tools against the implicit service workspace.
- [x] 4.2 Remove `workspaceId` from MCP input schemas and facade transport mapping while preserving schema versioning, size/result budgets, 10-second deadline, thread interruption, best-effort SDK protocol cancellation, sanitization, stderr-only logging, and read-only side-effect isolation.
- [x] 4.3 Add an in-process stdio smoke harness covering initialization, all six tools, invalid obsolete fields, unavailable external tools, deadline/interruption behavior, protocol-only stdout, server survival after a failed call, and shared-state visibility with the application composition.

## 5. Cutover evidence and deferred cleanup

- [x] 5.1 Make the new single-workspace launcher the documented service path and update configuration/API/UI/MCP documentation, explicitly stating fresh-state-only startup, unsupported legacy data, host-local SQLite/WAL/SHM, and rollback expectations.
- [x] 5.2 Statically confirm that the new transport paths do not construct Store/index/tool adapters directly, do not expose workspace registration or `workspaceId`, do not open legacy state, and do not add remote transport, auth, dependency, schema-migration, or deployment behavior beyond this design. Evidence: `cutover-static-review.md`.
- [x] 5.3 After explicit authorization, run the focused single-workspace HTTP/UI/MCP smoke suite against a temporary root and the narrow compile check; record exact commands, results, limitations, and recovery behavior. Evidence: `cutover-verification.md`.
- [x] 5.4 Update TODO, Verification, and remaining design notes with actual cutover evidence; identify physical removal of bypassed legacy multi-workspace code as a separate optional cleanup change only after all replacement flows pass. Evidence: `cutover-completion.md`.

## TODO

- [x] The production service uses one configured fresh workspace with no client-selectable workspace concept.
- [x] HTTP, bundled UI, and local stdio MCP use one application composition and expose the supported new engineering-knowledge flows.
- [x] Legacy data migration and physical legacy-code deletion remain explicitly out of scope.

## Verification

- [x] Static architecture review confirms a single resource owner and application-only transport dependencies. Evidence: `cutover-static-review.md`.
- [x] Focused compile and authorized fresh-workspace HTTP/UI/MCP smoke evidence are recorded. Evidence: `cutover-verification.md`.
- [x] No legacy workspace data or repository user data is modified by verification. Evidence: all integration fixtures use JUnit temporary roots as recorded in `cutover-verification.md`.

### Work Item 0.1 evidence

- [x] Static inventory traces the current launchers, configuration, legacy resource ownership, duplicated WebUI assets, MCP placeholder, and engineering-module constructor seams in `composition-inventory.md`.
- [x] No runtime service, database, index, configuration, or repository user data was opened or modified during the inventory.

### Work Item 0.2 evidence

- [x] `/api/v2`, `--workspace-root` / `workspace_root`, marker format/version `1`, readiness fields/status codes, and explicit obsolete-field errors are frozen in `contract-decisions.md`.
- [x] Design and capability requirements agree that validation and marker checks precede mutable Store/index construction.

### Work Item 0.3 evidence

- [x] `composition-design.md` defines one production root, construction/unwind order, sole resource owners, transport boundaries, readiness/degradation, and idempotent shutdown order.
- [x] Missing retrieval runtime, Context Builder factory, and workspace-free document/application seams are recorded as implementation Gates; transports are prohibited from bypassing them.

### Work Item 1.1 evidence

- [x] `SingleWorkspaceServiceConfig` is immutable and side-effect-free, accepts exactly one canonical root, normalizes it once, and exposes only the singleton internal `service` descriptor/catalog/map.
- [x] Focused tests cover CLI/TOML input, missing/repeated/mixed roots, every documented obsolete field, immutable catalog values, and derived-path confinement. Tests were authored but not run automatically.

### Work Item 1.2 evidence

- [x] `SingleWorkspaceLayout` validates the exact format/version marker before mutable resource construction and atomically publishes a fixed UTF-8 marker only for an absent or empty root.
- [x] Focused tests cover absent/empty initialization, repeat opening, and unchanged legacy, malformed, wrong-format, wrong-version, non-integer-version, and additional-field failures. Tests were authored but not run automatically.

### Work Item 1.3 evidence

- [x] `SingleWorkspaceKnowledgeService` owns one fresh document module, Store, retrieval runtime, immutable adapter profiles, application seams, and agent facade, with partial-construction unwind and dependency-ordered close.
- [x] Retrieval and Context Builder package internals remain behind public factories; transports receive only `SingleWorkspaceApplication` and `KnowledgeAgentFacade` views.
- [x] Query-time Git enrichment remains explicitly gated because the stable query has no full commit-ID selection; the concrete Git Adapter is composed for projection ingestion without inventing a selection contract.
- [x] A focused composition test verifies one application graph, fresh document subdirectory ownership, exposed application seams, and idempotent close. It passed both its original focused run and the combined 1.4 run.

### Work Item 1.4 evidence

- [x] `ServiceOperationCoordinator` is the single bounded application executor, closes admission atomically, rejects overflow or late work, applies caller deadlines, preserves caller interruption, and drains for a fixed grace before interrupting workers.
- [x] `SingleWorkspaceKnowledgeService` publishes READY only after complete construction, exposes an operator-safe readiness snapshot without the workspace root, and closes the coordinator before document, retrieval/Lucene, and Store resources exactly once.
- [x] Focused tests cover ready-to-closed visibility, late-work rejection, accepted-work draining, and deadline interruption. The authorized command `mvn -o -Dtest=ServiceOperationCoordinatorTest,SingleWorkspaceKnowledgeServiceTest test` passed 3 tests with no failures, errors, or skips on 2026-09-21.

### Work Item 1.5 evidence

- [x] `KnowledgeBaseDocumentApplication` owns one `KnowledgeBase` under the fresh `document` subdirectory, exhaustively maps every declared document/session operation, confines filesystem inputs to the service root, validates generic operation arguments, and closes its resource idempotently without using the legacy workspace registry or HTTP service.
- [x] `SingleWorkspaceLauncher` provides one CLI-over-TOML configuration path, rejects obsolete workspace keys before mutation, constructs the existing LLM client plus immutable bounded tool profiles, and is the production factory for `SingleWorkspaceKnowledgeService`. Omitted external tools use explicit unavailable profiles that return bounded diagnostics before process launch.
- [x] Focused adapter and launcher tests cover document/session execution, path escape rejection, canonical CLI precedence, side-effect-free configuration, omitted tools, relative executable rejection, and obsolete workspace rejection. The authorized command `mvn -o -Dtest=KnowledgeBaseDocumentApplicationTest,SingleWorkspaceLauncherTest test` passed 4 tests with no failures, errors, or skips on 2026-09-22.
- [x] The required offline compile command `mvn -o -DskipTests compile` compiled 285 main sources successfully on 2026-09-22. The existing missing `development` Maven profile warning remains non-blocking.

### Work Item 2.1 evidence

- [x] `SingleWorkspaceHttpApp` mounts only `/api/v2` health, readiness, status, engineering knowledge, ingestion, document/session, root/index, and maintenance routes; it defines no workspace lifecycle route and owns no Store, Lucene, tool adapter, or document persistence object.
- [x] Every business handler enters through the shared `ServiceOperationCoordinator`; HTTP shutdown stops listener acceptance before closing the shared composition.
- [x] `SingleWorkspaceDocumentApplication` resolves the document-module Gate and keeps legacy `WorkspaceRegistry` and `WorkspaceContext` outside the new transport path. Focused HTTP tests are deferred to Work Item 2.3 and were not run automatically.

### Work Item 2.2 evidence

- [x] One guard shared by every `/api/v2` route rejects `workspaceId`, `workspace`, and `workspace_id` from query, JSON, form/multipart fields plus `X-Workspace-Id` and `X-Workspace` headers before application admission, using the stable `OBSOLETE_WORKSPACE_FIELD` response.
- [x] HTTP serialization recursively removes internal workspace-routing keys from nested facade and document results while retaining provenance, freshness, completeness, and diagnostic values.
- [x] Facade outcomes map deterministically to HTTP success, invalid, not-found, unavailable, deadline, cancelled, and internal status families. Focused contract tests were authored but not run automatically.

### Work Item 2.3 evidence

- [x] `SingleWorkspaceHttpIntegrationTest` starts the real production composition and HTTP transport over a temporary fresh workspace, verifies readiness without absolute-root disclosure, ingests a document projection into the shared graph, and exercises HTTP search, controlled read, relation trace, and Context Builder results.
- [x] The focused fixture rejects obsolete configuration before workspace creation and obsolete request headers before application admission, verifies facade `NOT_FOUND` maps to HTTP 404, and confirms idempotent transport shutdown closes the document module once and refuses later connections.
- [x] The HTTP response boundary converts Java records and optionals to JSON-compatible value trees before recursively removing internal workspace-routing fields; this prevents facade responses from collapsing to JSON `null` while preserving nested provenance and diagnostics.
- [x] The authorized `mvn -o -Dtest=SingleWorkspaceHttpContractTest test` baseline and `mvn -o -Dtest=SingleWorkspaceHttpIntegrationTest test` smoke run each passed 2 tests with no failures, errors, or skips on 2026-09-21. Main-source compilation also passed; exact class outputs were removed and regenerated after Maven's timestamp-based incremental check initially retained stale bytecode.

### Work Item 3.1 evidence

- [x] The source use page no longer loads or renders a workspace selector, stores a selected workspace, annotates references with workspace routing, or retries zero-hit searches against alternate workspaces.
- [x] The source management page now renders one service-level status summary and retains document, ingestion, root, and index controls without workspace registration, selection, repair, removal, or inventory UI.
- [x] `webui/workspace.js` was removed from the source UI and request helpers now pass URLs and bodies without adding workspace routing. `/api/v2` endpoint migration remains scoped to Work Item 3.2; synchronization into `src/main/resources/webui` and browser/static assertions remain scoped to Work Item 3.3.
- [x] `mvn -o -DskipTests compile`, `node --check webui/app.js`, and `node --check webui/manage.js` passed on 2026-09-21. Browser-level behavior remains scoped to Work Item 3.3.

### Work Item 3.2 evidence

- [x] Source WebUI requests now use only the workspace-free `/api/v2` search, ask, session, document/revision/trash, ingestion, root/index, status, and knowledge-object endpoints; search consumes the engineering response envelope and opens stable object references through controlled knowledge reads.
- [x] Search/ask, health, session-list/detail, management refresh, root-list, document-list, and selected-document reads use request generations and identity checks so late responses cannot overwrite newer UI state.
- [x] The unsupported legacy multipart browser-upload control was removed from the source UI because the frozen v2 application contract exposes file and directory ingestion but no upload route; packaged asset synchronization and browser assertions remain owned by Work Item 3.3.
- [x] The authorized `node --check webui/app.js; node --check webui/manage.js` command passed on 2026-09-21.

### Work Item 3.3 evidence

- [x] The five source WebUI assets are byte-for-byte synchronized into `src/main/resources/webui`, and the obsolete packaged `workspace.js` asset is removed.
- [x] `SingleWorkspaceHttpApp` redirects `/` to the bundled use page and serves confined classpath assets under `/webui/*`; the existing HTTP integration fixture now asserts the packaged page and script load through the running service.
- [x] `SingleWorkspaceWebUiContractTest` asserts source/package identity, absence of `workspaceId` and legacy request helpers, exclusive `/api/v2` usage, and coverage of every retained UI endpoint. Tests were authored but not run automatically.
- [x] The authorized `mvn -o -Dtest=SingleWorkspaceWebUiContractTest,SingleWorkspaceHttpIntegrationTest test` command passed 4 tests with no failures, errors, or skips on 2026-09-22 after correcting the HTML assertion and forcing Maven to refresh timestamp-stale generated test/resource outputs.

### Work Item 4.1 evidence

- [x] `KnowledgeMcpServerMain` loads canonical CLI/TOML configuration through `SingleWorkspaceLauncher`, opens the shared production `SingleWorkspaceKnowledgeService`, and constructs the existing six-tool handler from its `KnowledgeAgentFacade` plus the implicit service workspace catalog.
- [x] Standalone shutdown is idempotent and closes MCP acceptance before the application composition; MCP startup failure closes the already-opened composition and preserves any close failure as suppressed evidence.
- [x] `KnowledgeMcpServerMainTest` exercises the production launcher against a temporary fresh workspace and asserts registration of exactly the six existing tools with protocol-only stdout. The authorized command `mvn -o -Dtest=KnowledgeMcpServerMainTest,KnowledgeMcpServerTest test` passed 2 tests with no failures, errors, or skips on 2026-09-22 (Maven total time 6.165 seconds).

### Work Item 4.2 evidence

- [x] All six MCP input and output schemas retain version `1.0`, strict unknown-field rejection, and their existing budgets while removing the client-visible `workspaceId` field; an obsolete supplied field is rejected before facade invocation.
- [x] `AgentToolRequestValidator` binds exactly one catalog workspace at construction and injects that internal identity into existing facade request DTOs, preserving repository confinement and output sanitization without exposing workspace routing at the transport boundary.
- [x] MCP structured/text responses recursively omit the internal workspace identity while retaining status, completeness, diagnostics, provenance, evidence, and fixed result limits; the existing deadline, interruption, protocol-cancellation, stderr-only, and read-only server paths are unchanged.
- [x] Focused schema, validator, handler, and stdio-harness tests were updated for the workspace-free contract but intentionally deferred for the final unified test run. The required `mvn -o -DskipTests compile` goal passed on 2026-09-22; the existing missing `development` profile warning remains non-blocking.

### Work Item 4.3 evidence

- [x] `KnowledgeMcpStdioHarnessTest` now opens a temporary fresh production composition, ingests a document projection through its application interface, initializes an in-process MCP stdio session, and invokes all six workspace-free tools against the same facade/resource graph.
- [x] The production fixture asserts shared-state visibility for search/get/trace/context, explicit `INCOMPLETE` degradation (`SOURCE_FAILED` after hybrid Ctags normalization and direct ripgrep `TOOL_UNAVAILABLE`), absence of `workspaceId`, and protocol-only JSON output without exception text.
- [x] Protocol fixtures retain obsolete-field rejection and survival after failed calls, distinguish best-effort SDK cancellation from the enforced deadline, and now assert that deadline cancellation interrupts the worker while the same stdio session remains usable.
- [x] The smoke harness was authored but intentionally not run pending the final unified test authorization. Static diff/coverage review passed, and the required `mvn -o -DskipTests compile` goal passed on 2026-09-22 with only the known missing `development` profile warning.

### Work Item 5.1 evidence

- [x] `SingleWorkspaceHttpServerMain` is the executable HTTP/WebUI entry point over `SingleWorkspaceLauncher`; the shared launcher now validates canonical TOML/CLI `port` input before opening resources, while MCP continues through the same configuration and composition factory.
- [x] `docs/single-workspace-service.md`, both README entry notices, and `config-example.toml` identify the single-workspace `/api/v2`, bundled UI, and local stdio MCP paths as the new deployment contract and label the remaining `/api/v1`/multi-workspace material as legacy-only.
- [x] The operations guide records fresh-state marker/version rules, unsupported legacy migration, absolute optional-tool paths, host-local SQLite/WAL/SHM and Lucene ownership, no shared-directory multi-process use, MCP SDK 2.0.1 cancellation limits, whole-workspace backup, and executable rollback without down-migration.
- [x] Static documentation/entry-point review passed. The initial compile exposed and corrected the checked-exception shutdown-hook adapter; the repeated `mvn -o -DskipTests compile` goal then passed on 2026-09-22 with only the known missing `development` profile warning. Focused tests remain deferred to the final unified run.

### Work Item 5.2 evidence

- [x] `cutover-static-review.md` records the reviewed production entry points and confirms that HTTP/MCP depend on application/runtime interfaces while concrete Store, Lucene/retrieval, and external-tool construction remains owned by `SingleWorkspaceKnowledgeService`.
- [x] Public HTTP, MCP schema, and bundled UI paths expose no workspace selector, registration lifecycle, or serialized `workspaceId`; occurrences in HTTP are rejection/sanitization guards and occurrences in MCP handlers are internal DTO values removed before serialization.
- [x] The production path validates the exact fresh-layout marker before mutable resource construction, MCP remains stdio-only, `pom.xml` is unchanged, and no authentication, remote transport, deployment automation, or legacy/server-side migration behavior was added.
- [x] Static review found no implementation correction required. Runtime smoke evidence remains deliberately deferred to the final unified test Work Item 5.3.

### Work Item 5.3 evidence

- [x] The authorized 15-class single-workspace runtime/HTTP/UI/MCP suite passed 42 tests with 0 failures, 0 errors, and 0 skips against temporary fresh roots; exact commands and scope are recorded in `cutover-verification.md`.
- [x] The final offline `mvn -o -DskipTests compile` completed with `BUILD SUCCESS`; no final distributable artifact was built.
- [x] A stale incremental-output failure was recovered by deleting only Maven-generated `target` content through `mvn clean` and recompiling the same focused scope. No source, repository workspace data, or legacy persisted state was deleted or opened.
- [x] The MCP harness now reflects SDK 2.0.1 schema-rejection framing, verifies no facade invocation for obsolete `workspaceId`, and distinguishes normalized Ctags degradation from direct ripgrep unavailability; its four stdio scenarios and the final combined suite pass.
- [x] Known limitations remain explicit: native optional tools were not configured, protocol cancellation is best-effort, and repository-wide/package/performance/remote/auth/deployment/multi-process validation was outside this focused authorization.

### Work Item 5.4 evidence

- [x] `cutover-completion.md` consolidates the completed product boundary, closes the early decision/factory/marker Gates with actual implementation evidence, and retains query-time Git selection as an explicit non-blocking limitation rather than inventing a contract.
- [x] All TODO and Verification items are satisfied by the static review and the authorized 42/42 temporary-workspace suite; no unchecked Work Item remains in this change.
- [x] Physical removal of `WorkspaceRegistry`, `WorkspaceContext`, `/api/v1`, the old HTTP client/CLI entry path, legacy tests, and legacy documentation is identified only as a separately approved optional follow-up. No cleanup Task/change was created and no legacy code or data was deleted.
- [x] Development completion is recorded separately from Git delivery, lifecycle synchronization, and archive; none of those operations was performed by this Work Item.
