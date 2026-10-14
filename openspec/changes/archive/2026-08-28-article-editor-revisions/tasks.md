## 1. Domain and persistence foundation

- [x] 1.1 Define the Article, current revision, revision history, trash, and projection lifecycle records while preserving existing document identities and metadata precedence.
- [x] 1.2 Add the SQLite migration/backfill that associates existing documents with stable Article identities and current revisions without changing their current searchable content.
- [x] 1.3 Add repository operations for loading full document content, revision history, current revision state, base hashes, deleted state, and Article-level metadata.
- [x] 1.4 Add repository tests for migration/backfill, stable identity, metadata inheritance, revision creation, and current-revision selection.

## 2. Safe file and revision operations

- [x] 2.1 Implement canonical root/path validation so document operations stay within the selected workspace and registered root or managed articles/uploads areas.
- [x] 2.2 Implement atomic text-file save with encoding and line-ending preservation for supported text formats.
- [x] 2.3 Implement immutable revision snapshot creation and history storage outside normal root synchronization and Search/Ask projection.
- [x] 2.4 Implement the reusable pure-text portion of base-hash conflict handling: non-overlapping line-level patch merge and conflict payloads containing base/server/submitted content or an equivalent diff.
- [x] 2.5 Implement root/article/upload deletion to workspace trash with 30-day retention metadata and restore-as-new-revision behavior.
- [x] 2.6 Implement per-file root purge progress, partial-failure reporting, and retry behavior without reprocessing files already moved successfully.
- [x] 2.7 Add service tests for path safety, save conflicts, successful merge, overlapping conflict rejection, restore, trash exclusion, and partial purge failure.

## 3. Application service and projection integration

- [x] 3.1 Add application-service operations for document view, explicit edit/save, revision history, restore, delete, and trash cleanup while preserving existing metadata-only callers.
- [x] 3.2 Reuse the existing workspace write serialization and projection batch/checkpoint recovery for current-revision updates.
- [x] 3.3 Ensure content saves re-run the existing text ingestion, chunking, source/AI metadata handling, and embedding behavior while retaining manual Article metadata.
- [x] 3.4 Ensure metadata-only saves reproject the current revision without creating a content revision.
- [x] 3.5 Ensure history and trash paths cannot be indexed by normal sync, rebuild, Search, or Ask operations.
- [x] 3.6 Add application-level tests proving file-save success and projection status are separately observable and recoverable after projection failure.

## 4. HTTP contracts and compatibility

- [x] 4.1 Add a workspace-scoped document detail response containing stable identity, current text, source path, format, metadata, base hash, current revision, and projection state.
- [x] 4.2 Extend the document save contract to accept content plus optional Article metadata, expected base hash, and explicit edit intent while preserving metadata-only PATCH behavior.
- [x] 4.3 Add HTTP operations for revision history, restore, delete, trash restore, and conflict responses with stable error semantics.
- [x] 4.4 Include stable document identity and workspace context in Search and Ask references without removing existing path/title/snippet fields.
- [x] 4.5 Add HTTP integration tests for view/edit/save, workspace targeting, conflict response, history/restore, delete/trash, and backward-compatible metadata updates.

## 5. WebUI reading and editing workflow

- [x] 5.1 Make Search and Ask reference titles/actions open a document in view mode using captured workspace and stable identity.
- [x] 5.2 Add an explicit transition from view mode to edit mode for supported text documents, with a pure-text editor and metadata fields.
- [x] 5.3 Add create mode for new text Articles saved under date-organized workspace `articles`, and retain the existing upload flow under `uploads` without forcing uploads through the editor.
- [x] 5.4 Add save-state presentation that distinguishes file saved, index pending, index completed, and index failed.
- [x] 5.5 Add conflict presentation with automatic-merge success details and a base/server/submitted diff when merge is rejected.
- [x] 5.6 Add history view, restore action, delete confirmation, trash restore, and partial purge retry feedback.
- [x] 5.7 Add WebUI tests or browser-level contract tests for workspace capture, reference navigation, explicit edit mode, save states, conflicts, history, and delete/restore flows.

## 6. Cleanup, verification, and documentation

- [x] 6.1 Implement scheduled or explicitly triggered cleanup for revisions older than three years and trash older than 30 days, never deleting the current revision of a live Article.
- [x] 6.2 Add observability for file save, revision creation, conflict, trash move, restore, projection pending, projection failure, and cleanup retry outcomes.
- [x] 6.3 Update user/developer documentation for root write behavior, revision retention, trash recovery, supported text formats, and the file-save/index-status distinction.
- [x] 6.4 Run repository-standard formatting, compile, unit tests, integration tests, and package verification; record exact commands and outcomes below.

## TODO

- [x] 6.5 Decide and document exact encoding/line-ending preservation behavior for each supported text format.
- [x] 6.6 Decide whether the first UI conflict view is a full visual diff or a textual conflict payload.
- [x] 6.7 Decide the scheduler/trigger and operational owner for revision/trash cleanup.
- [x] 6.8 Decide the later network-file loading trust boundary; it is not part of this change.

%% File rename/move identity tracking is intentionally deferred; first phase treats it as old Article removal plus new Article creation.

%% Task 2.4 provides the reusable pure-text merge seam; hash comparison and persistence integration remain in later tasks.

%% Task 1.1 introduces immutable service-layer lifecycle records only; persistence, migration, file operations, and projection integration remain in later tasks.

%% Task 1.2 persists lifecycle metadata and initial revision descriptors only; revision content snapshots and repository lifecycle operations remain in later tasks.

%% Existing upload provenance is classified as UPLOADS during backfill; other pre-existing documents default to ROOT because the repository migration has no workspace-path context for distinguishing managed articles.

%% Task 2.1 is intentionally lexical and side-effect free; symlink and filesystem checks belong to the later file-operation boundary.

%% Task 2.2 accepts an already-authorized path; path validation and symlink policy remain outside this component.

%% Task 2.2 preserves detected BOM, charset, and the first detected line-ending style; mixed line endings are normalized to that style.

%% Task 2.2 requires an atomic filesystem move; filesystems that do not support atomic replacement fail without replacing the target.

%% Task 1.3 exposes repository lifecycle operations; file decoding remains at the file-operation/application boundary.

%% Task 2.3 stores exact text snapshots as UTF-8 immutable files; source charset preservation remains the responsibility of current-file writes.

## Verification

- Not run during specification work.
- Task 4.4 static review: Ask references now expose `documentId` and HTTP adds the selected `workspaceId`; existing reference fields remain present. Tests were added/updated but not run per request.
- Expected validation: repository-standard verification script if available; otherwise Maven compile, focused service/HTTP/WebUI tests, full test suite, and package verification.
- Confirm that Search/Ask return only current revisions and that existing default-workspace and metadata-only API behavior remains compatible.
- Task 2.4 static review: merge service and unit-test paths inspected; tests/build intentionally not run per user instruction.
- Task 1.1 static review: immutable Article/revision/history/trash/projection records and focused model tests inspected; tests/build intentionally not run per user instruction.
- Task 2.1 static review: validator and unit-test paths inspected; tests/build intentionally not run per user instruction.
- Task 1.2 static review: SQLite migration/backfill and repository migration test paths inspected; tests/build intentionally not run per user instruction.
- Task 2.2 static review: atomic writer and focused tests inspected; tests/build intentionally not run per user instruction.
- Task 1.3 static review: Article/revision repository queries and lifecycle writes inspected; tests/build intentionally not run per user instruction.
- Task 2.3 static review: immutable history snapshot store and focused tests inspected; tests/build intentionally not run per user instruction.
- Task 4.1 partial implementation: document view now returns current text and metadata details; edit/save, history, restore, delete, and cleanup remain in later tasks.
- Task 4.2 implementation: PATCH with content now uses the serialized workspace write path, accepts expectedBaseHash and optional manual metadata, and leaves metadata-only PATCH behavior unchanged.
- %% Conflict integration uses a matching immutable revision snapshot when available; initial revisions without a snapshot still return a conservative conflict.
- Task 3.2/3.3/3.4 implementation: content saves run through WorkspaceContext.write and existing ingest/projection logic; metadata-only PATCH still updates manual metadata and reprojects without creating a revision.
- Initial ingestion and successful reprojection now transition the Article projection state to INDEXED; failures leave the pending batch/status recoverable through existing workspace recovery.
- Revision history read endpoint is available at GET `/api/v1/documents/{id}/revisions`; delete now has a DELETE document endpoint, while restore and trash listing remain pending.
- Task 2.5 implementation: DELETE moves source/history into `.kb/trash`, persists 30-day retention metadata, removes active projection data, and marks Article deleted; restore moves content back without overwrite and creates a new revision.
- Task 3.5 implementation: normal filesystem scanning explicitly excludes the workspace `.kb` tree, and deleted Articles are excluded from document enumeration and root disappearance cleanup.
- Task 4.3 implementation: revision history, document delete, trash listing, trash restore, and 409 document conflicts are exposed through workspace-scoped HTTP endpoints.
- Task 2.6 implementation: root PURGE processes each still-active file independently; successful files remain deleted/in trash, failures are recorded in root lifecycle and a retryable partial-failure error is returned.
- Task 6.1 implementation: explicit POST `/api/v1/maintenance/cleanup` removes expired trash and non-current revisions older than three years; cleanup reports per-entry failures and never removes a live Article's current revision.
- Task 5.1/5.2 implementation: Search and Ask references open a workspace-captured document overlay; viewing is read-only until Edit is selected, then content and Article metadata can be saved through the document PATCH contract.
- Task 5.3 implementation: POST `/api/v1/documents` creates text Articles under `articles/YYYY-MM-DD`; WebUI exposes a minimal New Article action, while upload endpoints remain separate.
- Task 5.4/5.5 implementation: editor save feedback uses the returned projection state, and HTTP 409 payloads render server/submitted text for manual comparison; available historical base snapshots are now used for non-overlapping automatic merge.
- Task 5.6 partial UI implementation: the document overlay can load revision history and confirm deletion; a basic Trash list/restore action is available, while richer history restore and purge retry feedback remain pending.
- Task 5.6 UI update: document delete confirmation and a basic Trash list/restore action are now available; purge retry feedback and richer history restore controls remain pending.
- History restore is now available through POST `/api/v1/documents/{id}/revisions/{revision}/restore`; the selected snapshot becomes a new current revision and the history list remains intact.
- Purge retry UI is now shown for roots in `purge_failed`, including the server-provided failure reason; retry reuses the existing per-file purge behavior.
- Task 6.2 implementation: workspace events are persisted and exposed at GET `/api/v1/maintenance/events`; save/delete/restore operations record Article/revision context.
- Task 1.4 implementation: repository tests now cover migration/backfill, stable identity, metadata inheritance, revision insertion, and current revision selection. Tests were added but not run per instruction.
- Task 3.6 partial implementation: application test now verifies content save writes the file, creates revision 2, retains manual metadata, and reports indexed projection; injected projection-failure recovery coverage remains pending.
- Task 4.5 partial implementation: HTTP integration test now covers create/view, stable document response, 409 conflict payload, metadata-only PATCH compatibility, and revision history; delete/trash restore and multi-workspace targeting cases remain pending. Test was added but not run.
- Task 5.6 implementation: document history restore, delete confirmation, trash listing/restore, and management-page purge retry feedback are now available.
- Task 5.7 implementation: WebUI static contract assertions now cover reference navigation, explicit editor mode, expected hash saves, conflict UI, history restore, new Article, trash, and purge retry controls. Tests are not yet run.
- Task 6.4 attempted `mvn test`; validation was blocked because this workspace has no `scripts/verify.sh`, no Maven Wrapper, and the current shell has no `mvn` executable. `mvn verify` was not attempted after the prerequisite command was unavailable.
- Task 2.7 partial implementation: validator, merge, snapshot, and trash-store tests cover path safety, successful/non-overlapping merge, overlapping conflicts, immutable history, trash movement, and restore collision protection. Direct save-conflict and partial-purge tests remain; tests were added but not run per instruction.
- Task 3.1 implementation: KnowledgeBase now exposes document view, content save, revision history, revision/trash restore, delete-to-trash, and retention cleanup operations; the existing metadata-only update path remains unchanged.
- Stale content saves now attempt a three-way merge from the matching immutable revision snapshot; overlapping edits or missing initial snapshots still return 409 with comparison payloads.
- Task 6.3 documentation is in `docs/article-editing.md`; encoding/line-ending behavior is delegated to `AtomicTextFileWriter`, first conflict UI uses textual server/submitted content, and network-file loading remains outside this change.
- Task 6.7 decision: cleanup is an explicit workspace-scoped maintenance API owned by the workspace administrator or an external scheduler; this change does not embed a scheduler.