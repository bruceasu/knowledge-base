## Context

Documents currently retain a generated summary and source-derived tags in SQLite, while Lucene indexes title, content, and summary only. Org and HTML are chunked for text but their native document metadata is not extracted. Browser uploads are copied into `<workspace>/uploads/<relativePath>` with replacement semantics, so same-name uploads can silently overwrite a prior source file.

The change must preserve SQLite as the metadata authority, retain the per-workspace write queue and projection model, and keep existing search callers compatible when they omit metadata filters.

## Goals / Non-Goals

**Goals:**

- Store stable document metadata with per-value provenance and preserve manual curation across syncs.
- Extract Org `#+TITLE` and `#+FILETAGS`, plus a safe HTML `<head>` allowlist: title/`og:title`, description/`og:description`, `keywords`, and `article:tag`.
- Generate bounded AI metadata through the configured text-only LLM adapter, validate it, and retain generator model/version/timestamp.
- Search title, tags, keywords, summary, and content with explicit relative boosts and exact tag filtering.
- Make upload storage collision-safe and deduplicate byte-identical content within a workspace.

**Non-Goals:**

- Parse general Org properties, arbitrary HTML metadata, or schema.org/JSON-LD.
- Infer metadata from arbitrary executable files.
- Provide semantic/vector retrieval ranking in this change.
- Allow LLM providers to read or mutate workspace files.
- Merge documents across workspaces or delete an existing document as an implicit result of an upload.

## Decisions

### Metadata authority and precedence

SQLite is the authority. Store document-level title and summary values with a source (`manual`, `source`, or `ai`) and store tags/keywords as individual normalized values with the same provenance. Effective values follow `manual > source > ai > existing filename/chunk fallback`.

This permits a subsequent sync to refresh source/AI values without destroying a user's edit. A single JSON metadata blob was rejected because it prevents filtering, provenance, and selective replacement efficiently.

### Source-native extraction

Org extraction recognizes `#+TITLE` and `#+FILETAGS`; heading tags and properties are excluded. HTML extraction uses an allowlist from `<head>` only. `keywords` is split, trimmed, normalized, deduplicated, and bounded; `article:tag` produces tags. Native source metadata is deterministic and can be refreshed from file content.

### AI metadata generation

The existing LLM abstraction receives a bounded prompt asking for a strict text/JSON metadata shape. Results are parsed and validated before persistence: title/summary size caps, normalized unique tags/keywords, a maximum count, and no arbitrary fields. Failure records no invalid metadata and retains deterministic/source metadata. AI values are supplementary, not an authority over manual or source values.

### Search projection and compatibility

Projection adds dedicated Lucene fields for effective title, tags, keywords, summary, and content. A multi-field query assigns defined boosts: title 4, tags 3, keywords 2, summary 1.5, content 1. Existing no-filter search remains valid. Tag filters use normalized exact values and compose with path-prefix filtering.

### Upload identity and collision policy

An upload is content-addressed by SHA-256 within its workspace. If the same hash already has a stored upload/document, the server returns a deduplicated result and reuses the existing document instead of copying or indexing again. Same-name but different-content uploads receive a new stable upload ID and a normalized human-readable filename below `uploads/`; they never replace an existing file. Original filename and source-relative path are retained as provenance, not identity.

Explicit replacement is out of scope for this change; a future update must address a document by stable ID and require confirmation.

### Fixed upload root, physical object storage, and declared roots

Every workspace owns exactly one upload content root, `<workspace>/uploads`. Browser uploads MUST be attributed to that root even when their physical object path or supplied relative path has nested directories. Physical bytes use a content-addressed object path such as `uploads/objects/sha256/ab/<hash>`; an upload batch/date and original relative path are provenance, not root identity or byte identity. This keeps the root list stable, prevents filename collisions, and permits same-content reuse.

External content roots remain independently managed. The management page adds one by syncing a directory and removes it with detach/purge. Startup declarations use repeatable CLI `--server-root <workspaceId>=<path>` and config `server_roots = "<workspaceId>=<path>,..."`; each declaration is synchronized through the workspace service after startup and is therefore persisted in that workspace's SQLite state. The fixed upload root is created lazily on the first accepted upload rather than at workspace registration.

## Risks / Trade-offs

- [Existing workspaces lack metadata tables] → Additive schema migration, null-safe repository reads, and rebuildable Lucene projection.
- [AI output is malformed or prompt-injected] → Use a constrained prompt, bounded parser, allowlisted fields, and treat retrieval content as untrusted evidence.
- [Metadata increases index size and write work] → Store document metadata once, project only effective values, and reproject only affected documents.
- [Hash deduplication obscures different user intent] → Keep upload provenance records even when content/document identity is reused.
- [Content-addressed objects are not human-browsable names] → Preserve original filename, relative path, batch ID, and received time as provenance and expose those values in management.
- [Configured startup sync can be expensive] → Treat each declared root as an explicit opt-in and run it through the existing serialized workspace write boundary.
- [HTML regex parsing is fragile] → Use a bounded head-only extractor and test malformed/mixed-case tags; do not claim full HTML conformance.

## Migration Plan

1. Add metadata, keyword, and upload-provenance tables/indexes additively within the workspace SQLite transaction boundary.
2. Backfill deterministic title/tag/summary metadata from existing document records where available.
3. Mark affected document projection batches pending and rebuild/project Lucene fields without changing document identity.
4. Deploy HTTP/UI additions as additive contracts; existing search calls retain current defaults.
5. Roll back code by leaving additive data in place; the preceding version ignores unknown tables and can use the prior index after an explicit rebuild if required.

## Open Questions

%% Confirm the exact public HTTP shape for document metadata editing and tag filters before implementation.
%% Confirm whether title/summary source metadata should be shown as separate values or only as effective values plus provenance in the management UI.
