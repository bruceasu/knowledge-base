## 1. Resolve contracts and preserve existing boundaries

- [x] 1.1 Decide external-path default policy, allow/forbid syntax, rule reload behavior, schedule format, and file edit semantics; record decisions in `design.md`.
- [x] 1.2 Inventory current workspace layout, shared document ingestion, project/code retrieval adapters, HTTP routes, and MCP facade seams; record reuse points without refactoring unrelated modules.
- [x] 1.3 Define compatibility behavior for project-aware search and additive MCP schema evolution while preserving shared-only requests.

## 2. Virtual-project metadata and path policy

- [x] 2.1 Add virtual-project metadata and resource-reference persistence to the shared workspace store, including stable IDs and per-project index/sync status.
- [x] 2.2 Implement canonical local-path validation for allow/forbid rules with forbid precedence, symlink handling, missing-file targets, and sanitized diagnostics.
- [x] 2.3 Add HTTP operations to create, read, update, remove virtual projects and add/remove file or directory references without changing source files.
- [x] 2.4 Add automatic Git-root provenance detection for referenced files without introducing repository registration.

## 3. Project content indexing and synchronization

- [x] 3.1 Add a service-owned index directory and lifecycle manager for each virtual project, reusing current extraction and index implementations where suitable.
- [x] 3.2 Implement explicit idempotent project synchronization that expands directory references, indexes supported documents/code, reconciles deletions, and reports unsupported or failed sources.
- [x] 3.3 Add a bounded configurable scheduled trigger that invokes the same synchronization operation and prevents overlapping updates per project.
- [x] 3.4 Expose project index freshness, last sync outcome, and partial/failure diagnostics through HTTP status operations.

## 4. Agent file operations and Memory usage

- [x] 4.1 Add policy-checked HTTP file operations for create, append, replace, edit, and delete, limited to resources associated with the selected virtual project.
- [x] 4.2 Ensure file mutations return explicit outcomes and coordinate safely with project synchronization; do not add a Memory-specific store or lifecycle.
- [x] 4.3 Document how Agents use ordinary project files for Memory and how changed files become searchable.

## 5. Project-aware retrieval and Agent search

- [x] 5.1 Extend the retrieval query with an optional virtual-project identifier; preserve shared-only behavior when it is absent.
- [x] 5.2 Retrieve bounded shared and selected-project candidates, normalize provenance, and apply deterministic additional ranking preference without comparing raw cross-source scores or calling an LLM.
- [x] 5.3 Extend local MCP `knowledge_search` with an additive optional project selector and validate unknown IDs with sanitized errors.
- [x] 5.4 Preserve result scope, project identity, source path/location, freshness, and partial-source diagnostics through HTTP and MCP responses.

## 6. Verification and operator guidance

- [x] 6.1 Add focused tests for path-policy precedence and symlink safety, project membership, sync reconciliation, file operations, project-aware ranking, and MCP validation.
- [x] 6.2 Compile the changed project using the repository-authorized narrow compile command; run tests only after explicit authorization.
- [x] 6.3 Update the user guide and configuration example for path rules, project management, sync triggers, Agent file operations, and scoped search.
- [x] 6.4 Complete task TODO and Verification evidence, document unresolved limitations, and confirm no source files are copied or deleted by project lifecycle operations.

## Verification

- 1.2 static inventory recorded in `design.md` from `SingleWorkspaceKnowledgeService`, `SqliteKnowledgeProjectionIngestion`, `HybridKnowledgeRetriever`, `SingleWorkspaceHttpApp`, and the MCP schema/handler/facade seams.
- 1.3 compatibility behavior recorded in `design.md`: omitted project selectors retain shared-only retrieval semantics; project selection and MCP response fields are additive, and incompatible MCP contract changes require a separately versioned change.
- No production code or unrelated modules changed for this inventory.
- No tests, builds, or compile checks run; the supervisor owns the compile-only validation plan.
- 2.1 `SqliteKnowledgeStore` now applies the additive `virtual-projects-v1` migration, persists virtual-project metadata, resource references, stable IDs, and index/sync lifecycle state, and provides bounded read/update/delete operations; focused persistence coverage was added without running it.
- 2.1 Project removal and reference removal operate only on store metadata; no source-file copy, move, or delete operation was introduced.
- 2.1 No tests, builds, or compile checks run; the supervisor owns compile-only validation.
- 2.2 `VirtualProjectPathPolicy` captures an immutable startup snapshot, canonicalizes existing and missing targets through the nearest existing ancestor, applies forbid-before-allow precedence with path-component boundaries, and denies external paths unless explicitly allowed.
- 2.2 Denials use stable path-free diagnostics and expose only a structured reason; symlink targets are resolved before rule evaluation, and no source files are modified by policy validation.
- 2.2 No tests, builds, or compile checks run; the supervisor owns compile-only validation.
- 2.3 Added a transport-independent virtual-project application boundary and HTTP CRUD/resource-reference routes under `/api/v2/virtual-projects`; resource additions pass through the canonical workspace-only path-policy snapshot before persistence.
- 2.3 Project and reference deletion only remove shared-store metadata; no source-file copy, move, or delete operation is used.
- 2.3 No tests, builds, or compile checks run; the supervisor owns compile-only validation.
- 2.4 Added a bounded, read-only `GitRepositoryLocator` that reuses the configured Git evidence profile, detects a worktree root and normalized repository-relative path for an already policy-approved resource, and treats non-Git/tool failures as absent optional provenance.
- 2.4 Persisted optional Git repository root and relative path metadata through the additive `virtual-project-git-provenance-v1` migration; no repository registration, source-file mutation, or authorization change was introduced.
- 2.4 Added focused locator and persistence coverage without running tests, builds, or compile checks; the supervisor owns compile-only validation.
- 3.1 Added the service-owned `index/virtual-projects` root and a bounded lazy `VirtualProjectIndexManager` that opens the existing Lucene projection per project, maps IDs to safe derived directories, protects active leases from eviction, and closes indexes without deleting source or derived directories.
- 3.1 Wired the manager into `SingleWorkspaceKnowledgeService` as an owned application resource and added focused lifecycle/path tests. No tests were run. The supervisor attempted the authorized compile-only Maven validation, but the JVM exited during javac because native memory allocation failed; no Java compiler diagnostic was reported.
- 3.2 Added `VirtualProjectSynchronizer` and the HTTP `POST /api/v2/virtual-projects/{id}/sync` operation. Synchronization revalidates canonical paths, expands directories without copying sources, rebuilds the service-owned project index from bounded UTF-8 document/code files, reconciles deletions by full replacement, and returns bounded per-source unsupported/failure diagnostics. Repeated runs use stable project/path IDs and are idempotent.
- 3.2 Synchronization persists `RUNNING`, `SUCCEEDED`, `PARTIAL`, or `FAILED` state and preserves usable indexed files when individual sources fail. No tests or builds were run; the supervisor owns compile-only validation.
- %% 3.2 The initial implementation uses bounded defaults of 2,048 discovered files and 2 MiB per source file; operator-configurable limits remain an open follow-up if deployment workloads require different bounds.
- 3.3 Added an optional fixed-delay scheduler configured by `virtual_project_sync_interval` (ISO-8601 `Duration`, bounded from `PT1M` through `P1D`) and `virtual_project_sync_concurrency` (1 through 32, default 2). The scheduler uses a bounded worker queue, waits for one cycle's submitted project work before the next fixed-delay trigger, and shares the explicit synchronization application operation.
- 3.3 Virtual-project synchronization now uses a per-project non-blocking lock; overlapping explicit or scheduled runs return `SKIPPED`, while different projects can synchronize concurrently. Scheduler threads are daemon-owned and closed before indexes/store during service shutdown.
- 3.3 No tests or builds were run; the supervisor owns compile-only validation.
- 3.4 Added the read-only `GET /api/v2/virtual-projects/{id}/status` operation. It returns the persisted project index status (freshness), synchronization outcome, start/completion timestamps, and the bounded last-sync diagnostic summary, while unknown project IDs retain the existing sanitized 404 behavior.
- 3.4 No tests or builds were run; the supervisor owns compile-only validation.
- 4.1 Added application-owned HTTP file operations under `/api/v2/virtual-projects/{id}/files` for create, append, replace, edit, and delete. Each target is revalidated by the canonical path policy and must match an existing project file reference or a descendant of a project directory reference before any read or write; external/unassociated paths are rejected with sanitized errors.
- 4.1 File content is strict UTF-8 text bounded to 2 MiB; create uses create-if-absent semantics, replace overwrites the complete content, edit requires exactly one non-empty `oldText`, append adds text, and delete removes only the selected project's regular file target. Successful responses include an explicit operation outcome and indicate that synchronization is required for search visibility.
- 4.1 No source files are copied or moved by these operations. No tests or builds were run; the supervisor owns compile-only validation.
- 4.2 File mutations and explicit or scheduled synchronization now share one fair per-project coordinator. Mutations serialize with index rebuilds, while overlapping synchronization retains its bounded `SKIPPED` outcome; successful mutations continue to return `SUCCEEDED` plus `requiresSynchronization=true`.
- 4.2 Agent Memory remains ordinary project file content; no Memory-specific store, classifier, or lifecycle was added. No tests or builds were run; the supervisor owns compile-only validation.
- 4.3 Updated `docs/user-guide.zh.md` with the ordinary-project-file workflow for Agent Memory, bounded file-operation semantics, explicit/scheduled synchronization, and the point at which changed files enter the project-derived index.
- 4.3 No tests, builds, or compile checks were run; the supervisor owns compile-only validation.
- 5.1 Extended `KnowledgeQuery` and `ValidatedKnowledgeQuery` with a normalized optional `virtualProjectId`, while retaining the existing shared-scope constructors and empty-selector behavior.
- 5.1 Existing candidate-source behavior is unchanged when the selector is absent; project candidate retrieval and ranking remain scoped to later 5.2 work.
- 5.1 No tests, builds, or compile checks were run; the supervisor owns compile-only validation.
- 5.2 Added a bounded virtual-project Lucene candidate source that reads only the
  selected project's service-owned index, normalizes its source path, freshness,
  provenance, source rank, and project identity, and reports sanitized partial-source diagnostics.
- 5.2 Shared-only queries retain the existing candidate sources and ordering; the
  project source is inert when `virtualProjectId` is absent. Project candidates receive
  a deterministic preference before the existing freshness, match-category, provenance,
  source-rank, and stable-fallback keys without comparing raw backend scores or calling an LLM.
- 5.2 No tests, builds, or compile checks were run; the supervisor owns compile-only validation.
- 5.3 Extended the local MCP `knowledge_search` input additively with optional `virtualProjectId`; omitted selectors retain the existing shared-only request path, while supplied selectors are checked for bounded syntax and existence before facade retrieval. Unknown or unavailable projects return status-only sanitized diagnostics without exposing IDs or local paths. Production MCP startup resolves project existence through the existing virtual-project application boundary; the MCP schema remains version `1.0`.
- 5.4 Retrieval items now retain optional virtual-project identity through the ranker and facade, and agent search results add `scope` plus `virtualProjectId` without changing shared-only constructors. HTTP search accepts the additive project selector and serializes source path/location, freshness, and top-level partial-source diagnostics; MCP declares the additive result fields and keeps diagnostics bounded/sanitized. Absolute project source paths are represented as safe project-scoped relative display paths for MCP compatibility; no source files are modified.
- 5.4 No tests, builds, or compile checks were run; the supervisor owns compile-only validation.
- 5.3 No tests, builds, or compile checks were run; the supervisor owns compile-only validation.
- 6.1 Existing path-policy tests cover forbid precedence, path-component boundaries, missing targets, symlink resolution, and sanitized diagnostics; added focused coverage for project membership and all bounded file operations, sync reconciliation after source deletion, selected-project ranking and identity preservation, and MCP project-selector validation.
- 6.1 No tests or builds were run; the supervisor owns compile-only validation. The new tests are limited to temporary directories, the local SQLite store, and existing in-process retrieval/MCP seams; no source files are copied or deleted by project lifecycle code.
- 6.2 The repository routing plan authorizes the narrow compile command `mvn -DskipTests compile`. The supervisor attempted this compile-only command, but the JVM exited during javac because native memory allocation failed; no Java compiler diagnostic was reported and no tests were run.
- 6.3 Updated `docs/user-guide.zh.md` with virtual-project CRUD/resource routes, canonical path-policy behavior, explicit and scheduled synchronization, status diagnostics, Agent Memory/file-operation workflow, and HTTP/MCP scoped-search examples. Updated `config-example.toml` with the bounded scheduler keys and the current workspace-only path-policy/default limitation.
- 6.4 Lifecycle evidence: project CRUD, resource membership changes, synchronization, index lifecycle, and file-operation code paths do not copy, move, or delete referenced source files; only an explicit Agent `DELETE /files` operation deletes an associated source file. Project removal and resource removal delete metadata/membership only. No tests were run; compile evidence is recorded in 6.2.

## TODO / Limitations

- %% 3.2 Synchronization keeps the bounded defaults of 2,048 discovered files and 2 MiB per source file; operator-configurable limits remain a follow-up for larger deployments.
- %% 6.3 The current launcher exposes scheduler settings in TOML, but does not yet expose configurable external allow/forbid path lists; runtime policy therefore remains workspace-only by default. Do not use external project references until a policy configuration extension is implemented and validated.
- %% 6.4 The supervisor must repeat or repair the narrow compile-only validation if native memory capacity becomes available; runtime tests remain intentionally unrun without explicit authorization.
