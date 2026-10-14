## Context

The knowledge base stores its operational state and search projection in the workspace, while user-selected root folders remain the primary source of content. The current service can ingest files, return search and ask references, edit document metadata, and reproject a document, but it does not expose the full text as an editable document or preserve content revisions.

The change crosses the HTTP, WebUI, application/service, persistence, file-operation, ingestion, and Lucene projection boundaries. It must preserve existing root-folder behavior, default workspace API behavior, metadata precedence, and the existing recoverable projection model.

## Goals / Non-Goals

**Goals:**

- Give each logical Article a stable identity independent of its current revision file.
- Read and edit text files from registered roots through an explicit view/edit flow.
- Create online articles under workspace `articles` and continue supporting uploaded content under `uploads`.
- Save content as immutable revisions, retain the current revision for Search/Ask, and support history viewing and restore-as-new-revision.
- Detect concurrent edits with a base hash, safely auto-merge only non-overlapping line changes, and expose conflicts for user resolution.
- Move deleted content to a 30-day workspace trash with recoverable, retryable root purge behavior.
- Keep history and trash outside normal ingestion and search projection.
- Keep file-save state distinct from index-projection state.

**Non-Goals:**

- Multi-user collaboration, branches, semantic merge, or approval workflows.
- Browser editing of binary documents.
- Tracking Article identity across file rename or move in the first phase.
- Replacing local editors or providing a full-featured IDE.
- Direct network URL fetching as part of this change.

## Decisions

### Stable Article identity and revision ownership

Introduce a logical Article identity with a current-revision reference. A revision records an immutable text snapshot, content hash, format, source path, and creation metadata. Existing documents must be associated with a stable Article identity without changing their current Search/Ask meaning.

The first phase uses the workspace plus normalized canonical path to associate an existing source file. Content hash is used for revision identity and optimistic concurrency, not as the sole Article identity. File rename or move is treated as an old Article disappearing and a new Article appearing.

Alternative considered: use content hash as the identity. Rejected because identical content can exist in different files and paths.

### Source location policy

Root files remain in their registered root folders and are edited in place after conflict validation. New online articles are stored under the workspace `articles` directory organized by date. Browser uploads remain under `uploads`. History and trash are stored in workspace-managed `.kb` areas and are excluded from normal root synchronization and projection.

Alternative considered: copy every root file into `articles` before editing. Rejected because it would create two competing sources of truth.

### Save and projection boundary

The application service performs an ordered save: validate the Article and expected hash, create the revision/history snapshot, atomically replace the current source file, update Article state and metadata, then enqueue or execute the existing projection path. File persistence is the user-visible save boundary; index projection may briefly lag and must expose its state.

SQLite remains authoritative for Article/revision lifecycle state and Lucene remains a rebuildable projection. Existing workspace write serialization and projection checkpoint/retry behavior must be reused.

Alternative considered: require file write and Lucene commit to be one transaction. Rejected because the file system, SQLite, and Lucene cannot provide a single atomic transaction.

### Conflict handling

The editor sends the content hash observed when the document was opened. If the source has changed, the service attempts a line-based patch merge only for supported plain-text content. Non-overlapping changes may be saved as a new revision. Overlapping changes return a conflict payload containing the base, server, and submitted versions or a diff representation. The service never silently chooses one overlapping edit.

Alternative considered: last-write-wins. Rejected because WebUI is an auxiliary editor used alongside local editors and must not discard local work.

### Deletion and retention

Delete moves the current source file and associated managed revision material into the workspace trash instead of leaving a root tombstone. Search/Ask exclusion and Article deleted state are persisted before the operation is reported complete. Trash is retained for 30 days and then garbage-collected, while at least the current revision is retained for a live Article.

Root purge is per-file and retryable: successful moves remain in trash, failures remain at their original location, and the root reports a partial-failure state.

Alternative considered: immediately unlink files or leave tombstones. Immediate unlink is not recoverable; tombstones pollute root folders and create a second synchronization protocol.

### Metadata ownership

Title, summary, keywords, and tags remain Article-level metadata. Creating a new content revision inherits the effective/manual metadata. Metadata-only edits do not create a content revision but reproject the current revision. Existing source and AI metadata precedence remains unchanged.

### Test seam

The primary verification seam is the workspace-scoped HTTP contract backed by the existing application service: document view/edit/save/delete/restore behavior is observed through HTTP responses and subsequent Search/Ask results. Unit tests cover pure conflict/retention rules only where the HTTP seam cannot express the edge case.

## Risks / Trade-offs

- **[Risk]** WebUI can write every registered root. → Restrict operations to text files inside canonical registered roots, validate paths, use atomic replacement, and require explicit edit/delete actions.
- **[Risk]** File write succeeds while projection fails. → Persist projection batches/checkpoints, expose separate statuses, retry on restart, and support rebuild.
- **[Risk]** Cross-volume move to trash is not atomic. → Use copy-then-verify-then-remove with recoverable partial-failure status.
- **[Risk]** Revision retention consumes unbounded storage before the three-year cleanup. → Keep cleanup separate from live save and never remove current revision; report cleanup failures for retry.
- **[Risk]** Format-specific metadata extraction changes user metadata unexpectedly after save. → Preserve manual metadata and apply existing source/AI precedence; limit the first editor to text formats already supported by ingestion.
- **[Risk]** Existing clients do not know new reference fields. → Add fields without removing existing response fields and preserve default workspace behavior.

## Migration Plan

1. Add Article/revision lifecycle persistence with a migration that associates existing documents to stable Article identities and current revisions without changing indexed content.
2. Backfill current revision metadata for existing documents and keep legacy document paths readable.
3. Add document view/edit/save and reference-link contracts while retaining existing metadata-only compatibility behavior.
4. Enable current-revision projection and exclude history/trash from sync and search.
5. Roll back by disabling the new editor routes; keep source files and legacy document/index data intact. Revision records and managed files can remain unused until a forward migration is resumed.

## Open Questions

- Exact text encoding and line-ending preservation rules for each supported format.
- Whether the first UI should expose a full diff viewer or a textual conflict payload.
- The scheduler/trigger for three-year revision and 30-day trash cleanup.
- Whether network-file loading is added in a later change and which trust boundary it uses.

%% The first phase intentionally treats rename/move as delete plus create; identity tracking across moves needs a separate decision.
