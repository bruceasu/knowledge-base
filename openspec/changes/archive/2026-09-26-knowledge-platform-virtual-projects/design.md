## Context

The service already runs as a single local process with one configured workspace, HTTP and stdio MCP entry points, document indexing, hybrid retrieval, ripgrep code search, Ctags symbols, Git evidence, and a shared application composition. Existing retrieval uses deterministic candidate fusion; LLM calls are used for summaries/metadata and explicit ask responses, not ordinary search ranking.

The next capability adds named virtual projects over arbitrary local source paths while keeping the configured workspace as the shared knowledge store. It must not turn each Git repository into a separately registered service workspace or duplicate the whole retrieval stack per project.

## Goals / Non-Goals

**Goals:**

- Keep one process and one service workspace for shared knowledge and project metadata.
- Persist virtual-project metadata in the shared knowledge store and project indexes in separate per-project directories.
- Let a project reference multiple local files and directories, subject to a single allow/forbid path policy used for reads and writes.
- Support explicit synchronization and scheduled synchronization through one application operation.
- Search shared knowledge alone by default, or combine it with a selected project using deterministic project-aware ranking.
- Let Agents manage project files through bounded service operations; keep Agent Memory as ordinary project files.
- Reuse existing document and code index mechanisms behind the current application/retrieval boundaries.

**Non-Goals:**

- Multiple server workspaces, remote sharing, a Web UI, filesystem watchers, LSP, or Codex SDK.
- Copying or relocating source files into the service workspace.
- A separate Agent Memory database, schema, index engine, or lifecycle.
- Calling an LLM for ordinary search, candidate fusion, or project boosting.

## Decisions

### Existing workspace and adapter seams inventory

The current service is composed as one workspace by `SingleWorkspaceKnowledgeService.open`.
`SingleWorkspaceLayout` provisions the workspace directories; `SqliteKnowledgeStore` at
`state/engineering.sqlite` is the authoritative new-format metadata/projection store.
The document application is opened from `document/` through `DocumentModuleFactory`, and
the engineering retrieval projection uses `index/engineering/`. This composition point is
the reuse seam for adding a shared virtual-project catalog and service-owned project index
lifecycle; a project implementation should not create another server workspace or fork the
whole runtime composition.

Shared document ingestion currently enters through `SingleWorkspaceDocumentApplication`
and its document module (`KnowledgeBase` in the legacy document service). The engineering
projection path is separately exposed by `KnowledgeProjectionIngestion`; its production
implementation, `SqliteKnowledgeProjectionIngestion`, validates bounded source units,
normalizes them, checks current stored state, and persists projections through the shared
SQLite store. `IngestionRequest` and `IngestionSourceUnit` are the adapter-facing contracts.
These are candidate reuse points for document/code extraction and projection, while project
index isolation remains a responsibility of the new project lifecycle boundary.

Retrieval's public seam is `KnowledgeRetriever`/`KnowledgeQuery`. `RetrievalRuntimeFactory`
composes the current bounded `HybridKnowledgeRetriever` from candidate sources: the Lucene
BM25 lexical projection, document/knowledge retrieval, ripgrep code search, and Ctags symbol
search. `CandidateFusionPreprocessor` and `TieredCandidateRanker` own normalization and
deterministic fusion; source-specific adapters live under `adapter/document`,
`adapter/codesearch`, and `adapter/symbol`. Git evidence enrichment is a separate provenance
adapter. Project-aware retrieval can therefore be introduced at the query/runtime/fusion
boundary without changing individual source adapters or comparing their raw scores.

HTTP routes are registered in `SingleWorkspaceHttpApp.registerRoutes`. Knowledge search,
object retrieval, trace, and context use the `SingleWorkspaceApplication` application
surface; shared document and administration routes are delegated through
`SingleWorkspaceDocumentApplication.Operation`. New project administration and file
operations should join this application boundary with dedicated operations rather than
putting policy or indexing logic in route handlers.

The local stdio MCP facade has a similarly explicit seam: `KnowledgeAgentToolSchemas`
defines versioned tool contracts, `AgentToolRequestValidator` validates input,
`KnowledgeMcpToolHandlers` maps validated calls to `KnowledgeAgentFacade`, and
`DefaultKnowledgeAgentFacade` delegates to application retrieval/search services. Additive
project selection belongs in the search request/schema and these facade mappings; MCP
transport and server lifecycle remain unchanged.

No code modules were refactored as part of this inventory.

### Keep shared workspace and virtual projects as separate scopes

The configured workspace remains the owner of public documents, virtual-project metadata, service state, and synchronization configuration. A virtual project is a logical set of canonical file/directory references and a separate derived index directory under service-owned storage. The original files remain in place.

Alternative considered: treating each Git root as a registered workspace. Rejected because a Git repository is source metadata, while a virtual project may combine multiple repositories, individual files, and non-code materials.

### Store project metadata centrally and project projections separately

Use the existing authoritative local metadata store for project identity, descriptions, membership references, and index/sync state. Keep each project's rebuildable search projection in its own directory, opened through the existing retrieval/index implementation where possible. A project index is derived from the source references and can be rebuilt without changing public knowledge.

Alternative considered: one new service/database/index stack per project. Rejected because it multiplies lifecycle and resource management. The physical index directories remain isolated while the implementation remains shared.

### Enforce one canonical path policy at every boundary

Add an application-level path-policy seam that resolves local paths before membership changes, scans, sync, file reads, and file writes. External paths are denied by default: paths under the configured shared workspace are eligible, while paths outside it require an explicit allow rule. A forbidden rule wins over every allowed rule. Existing paths are checked against their resolved canonical target so symlinks cannot escape a forbidden root. Missing file targets are checked using the nearest existing canonical parent plus the requested normalized relative suffix; the exact implementation must prevent symlink races at write time.

Configure allow and forbid as lists of absolute local filesystem paths in the service's existing application configuration (YAML properties bound at startup). A file rule matches that canonical file; a directory rule matches that directory and descendants on path-component boundaries, not similarly prefixed sibling names. Rules are canonicalized with the same path resolver used for access checks. The workspace default is an implicit allow only when no explicit forbid rule matches; external paths need an explicit allow, and forbid always takes precedence. Do not support glob syntax in the first release. Configuration is a startup snapshot: changes take effect after a service restart, not through partial hot reload. This keeps each operation on one consistent policy snapshot.

External paths are supported only when the active policy allows them. Do not infer permission solely from Git presence. Git discovery supplies provenance metadata, not authorization.

### Use HTTP for administration and writes; keep MCP search read-only

Use HTTP application operations for virtual-project CRUD, membership changes, synchronization/status, and file create/append/replace/edit/delete. Extend the existing read-only MCP search request additively with an optional virtual-project identifier. MCP remains local stdio and read-only; Agents that need file mutation use the bounded HTTP operations.

Alternative considered: add mutating MCP tools. Deferred to avoid widening the current MCP write boundary; existing Agents can call the local HTTP API through their configured HTTP/tool capability.

### Share the synchronization operation between explicit and scheduled triggers

Implement one idempotent project synchronization application operation. HTTP can trigger it directly; a local scheduled runner invokes the same operation with bounded ISO-8601 duration configuration (`Duration`, for example `PT1H`). The schedule is a fixed delay measured from completion of one scheduled run to the next, avoiding catch-up bursts. Scheduling is disabled when unset; when configured, the interval must be from `PT1M` through `P1D`. Directory membership is expanded during sync, so newly added files under a referenced directory are discovered on the next run. Deletions and unreadable/unsupported files are reflected as index state, not silently retained as current results. Explicit and scheduled triggers share a per-project no-overlap coordinator; an already-running project sync is reported as skipped/in-progress, while other projects may proceed within the configured bounded scheduler capacity.

File `replace` overwrites the complete UTF-8 text content. File `edit` is a bounded literal text patch with `oldText` and `newText`: it succeeds only when `oldText` occurs exactly once, and reports a conflict without writing when it occurs zero or multiple times. Empty `oldText` is invalid. Both operations apply to UTF-8 text files only and use the same project-membership and path-policy checks as other writes. Append adds UTF-8 text at the end; create fails if the target already exists. Accepted writes become searchable on the next explicit or scheduled synchronization; writes do not run a separate indexing path.

No file watcher is added. The schedule is optional, uses the configured ISO-8601 fixed delay, and skips a trigger for a project that is already syncing.

### Keep retrieval deterministic and preserve source evidence

Collect candidates from the shared index and selected project index using bounded source adapters. Normalize project identity, source kind, location, freshness, and provenance before ranking. Apply a documented project preference in the application-level fusion step; do not compare raw Lucene, ripgrep, or Ctags scores across sources and do not call an LLM. With no project identifier, query only shared knowledge. With a project identifier, query shared knowledge plus that project.

Return source identity, original path, supported symbol/line location, freshness, and partial/failure diagnostics. Existing public result fields remain compatible; project fields are additive.

Compatibility behavior: omitting the virtual-project identifier MUST follow the existing shared-only retrieval path, including its candidate sources, limits, ordering rules, and existing result meanings. The identifier is an optional additive query field; existing callers do not need to change their requests. Selecting a project adds project candidates and an explicit project preference only for that request. Existing response fields and their meanings remain stable, while scope, project identity, path/location, freshness, and partial-source diagnostics are additive fields. Existing MCP input schema version `1.0` remains valid: adding this optional input and additive output fields is compatible evolution under the current schema version. Do not make the selector required, reinterpret omitted values, rename/remove fields, or alter shared-only ranking as part of this change. Any incompatible contract change requires a separately versioned change.

### Treat Agent Memory as normal project content

Memory files use the same membership, file-operation, sync, and index pipeline as other project files. No memory-specific classifier, ownership model, or retention worker is added in this change. An Agent can create or edit files explicitly through HTTP operations.

### Identify Git roots automatically for provenance

When a source path is inside a Git working tree, record the nearest detected repository root and relative path as source metadata. Do not require repository registration. Repository detection does not change file selection, permissions, or index ownership.

## Risks / Trade-offs

- **Arbitrary local paths widen file access** → Apply the same canonical allow/forbid policy before every scan, read, and write; forbidden rules always win; report sanitized denials.
- **Multiple project indexes consume file handles and disk** → Open indexes through a shared lifecycle manager, lazily where feasible, and expose per-project status; do not keep an unbounded number open.
- **Project and shared candidates use different source scores** → Normalize candidate categories and use deterministic tiered/reciprocal ranking; never compare raw cross-source scores.
- **Scheduled and explicit sync may overlap** → Route both through one per-project coordinator with idempotent updates and bounded concurrency.
- **Removed members or source files leave stale candidates** → Reconcile membership and source inventory during sync and surface incomplete state if reconciliation fails.
- **Agent file edits can race with synchronization** → Serialize mutations per project/resource or use revision checks and reindex after accepted writes.
- **Some referenced file types are not extractable** → Preserve the reference and return an explicit unsupported/unindexed diagnostic; do not claim it is searchable.
- **External-path access can expose local files** → Deny external paths by default and require an explicit allow rule; keep forbid precedence and canonical path checks at every access boundary.

## Migration Plan

1. Add virtual-project metadata and index directories under the existing service-owned workspace layout; do not alter existing public documents or indexes.
2. Initialize the virtual-project catalog lazily or through an additive schema/layout version decision made before implementation.
3. Add project CRUD, path-policy checks, file operations, and one explicit synchronization operation.
4. Add the scheduled trigger using the same synchronization operation.
5. Add project-aware retrieval and additive MCP search input while keeping no-project requests on the current shared-only behavior.
6. Rollback by disabling project operations and removing only project-derived indexes/metadata after an explicit operator action; never delete referenced source files as part of project removal.

## Open Questions

- Which file types must be extractable in the first release, and what file-size limits apply?
- Should project metadata and file mutation endpoints use the existing local HTTP trust boundary, or require a separate local credential?
- What deterministic project boost and deduplication key should be used across shared and project candidates?
- Should file writes trigger immediate incremental indexing, or only the next explicit/scheduled sync?

%% Historical specs under openspec/changes/archive/ are reference material only. New requirements are expressed by this active change and must not rewrite archived decisions.
