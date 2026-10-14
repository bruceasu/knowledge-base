## 1. Metadata persistence and migration

- [x] 1.1 Define additive SQLite tables and repository methods for metadata values, keywords, provenance, and upload content identity.
- [x] 1.2 Implement effective metadata resolution with `manual > source > ai > fallback` precedence.
- [x] 1.3 Add a backward-compatible workspace migration/backfill path and projection refresh for existing documents.

## 2. Metadata extraction and generation

- [x] 2.1 Add deterministic extraction of Org `#+TITLE` and `#+FILETAGS` without parsing Org properties or tag declarations.
- [x] 2.2 Add safe HTML `<head>` extraction for the approved title, description, keywords, and article tag allowlist.
- [x] 2.3 Add bounded, validated AI metadata generation and provenance recording through the text-only LLM adapter.

## 3. Search projection and contracts

- [x] 3.1 Project effective title, tags, keywords, summary, and content into Lucene with the specified relative boosts.
- [x] 3.2 Add exact normalized tag filtering to service and HTTP search contracts while preserving existing unfiltered requests.
- [x] 3.3 Expose effective metadata and provenance in additive document/search response fields.

## 4. Safe upload identity

- [x] 4.1 Replace upload-path overwrite behavior with normalized, unique, stable upload storage paths.
- [x] 4.2 Calculate workspace-scoped SHA-256 identity and return a deduplicated result for identical content without creating a second physical copy or document.
- [x] 4.3 Preserve original filename and relative-path provenance for uploads, including same-name different-content uploads.
- [x] 4.4 Attribute every browser upload to the fixed workspace `uploads` root and persist upload batch/date provenance independently of physical storage paths.
- [x] 4.5 Add repeatable CLI and configuration startup root declarations, routed through the workspace service and persisted as normal roots.

## 5. Management experience

- [x] 5.1 Add management-page document metadata inspection with effective values and provenance.
- [x] 5.2 Add local management editing for manual title, tags, keywords, and summary, including affected-document reprojection.
- [x] 5.3 Update upload feedback to distinguish created, deduplicated, and rejected collision-safe outcomes.

## 6. Tests and documentation

- [x] 6.1 Add repository/service tests for provenance precedence, Org/HTML extraction, AI validation failure, and upload deduplication/collision behavior.
- [x] 6.2 Add HTTP-boundary tests for metadata fields, tag filtering, manual edits, and backward-compatible unfiltered search.
- [x] 6.3 Add Lucene ranking/projection tests for metadata fields and relative boosts.
- [x] 6.4 Update configuration and user documentation for metadata sources, curation, search filters, AI generation, and upload deduplication.

## Verification

- [x] Additive metadata values, keywords, and upload identity tables are created and round-tripped through the repository.
- [x] Effective title, summary, tags, and keywords resolve with source precedence and expose provenance.
- [x] Existing documents are backfilled idempotently and scheduled for projection refresh on metadata migration.
- [x] Org file-level title and file tags are extracted and persisted as source metadata without reading properties or declarations.
- [x] HTML metadata extraction is bounded to `<head>` and restricted to the approved title, description, keywords, and article tag allowlist.
- [x] AI metadata output is bounded, allowlisted, validated before persistence, and records generator provenance without replacing deterministic metadata on failure.
- [x] Lucene projections include effective title, tags, keywords, summary, and content with relative boosts 4, 3, 2, 1.5, and 1.
- [x] Search accepts optional normalized exact tag filters while requests without filters retain the existing behavior.
- [x] Search hits and document metadata responses expose effective values with source and generator provenance.
- [x] Upload paths are normalized and collision-safe without replacing an existing physical file.
- [x] Upload SHA-256 identity is persisted per workspace and repeated identical uploads return a deduplicated result.
- [x] Upload provenance retains original names/relative paths and uses one fixed workspace uploads root with batch/time fields.
- [x] Repeatable CLI/configured startup roots are synchronized through the workspace service.
- [x] Management page can inspect effective document metadata/provenance and save manual metadata with reprojection.
- [x] Upload feedback distinguishes created and deduplicated outcomes; rejected uploads continue through the error feedback path.
- [x] Verify an existing workspace migrates without losing documents, tags, summaries, or search availability.
- [x] Verify Org `#+FILETAGS` and allowlisted HTML head metadata produce normalized source metadata.
- [x] Verify manual metadata survives source sync and failed/malformed AI metadata generation.
- [x] Verify title, tags, keywords, summary, and content are all searchable with the specified relative boosts and exact tag filtering.
- [x] Verify identical uploads are deduplicated and same-name different-content uploads never overwrite an existing file.
- [x] Verify a nested directory upload creates documents under one fixed `uploads` root and retains batch/path provenance.
- [x] Verify configured and CLI-declared roots synchronize at startup and remain independently manageable in the management page.
- [x] Run focused repository, Lucene, HTTP, and WebUI tests subject to explicit user authorization.

## TODO / Open Questions

%% Resolved: additive metadata endpoints are GET /api/v1/documents and GET/PATCH /api/v1/documents/{id}; existing search fields remain compatible.
%% Resolved: AI metadata uses the existing text-only LlmClient adapter and remains disabled/failure-safe when no valid JSON is returned; provider-specific flags are not required.