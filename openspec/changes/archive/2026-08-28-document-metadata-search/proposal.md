## Why

The knowledge base already stores titles, summaries, and derived tags, but tags do not participate in lexical retrieval and the system has no stable metadata contract for source-native metadata, AI-derived keywords, manual curation, or upload provenance. Users therefore cannot reliably find documents by their subject metadata or safely ingest files with colliding names.

## What Changes

- Add a document metadata model for title, summary, tags, keywords, provenance, and metadata generation state.
- Extract source-native metadata from Org `#+FILETAGS` and a safe allowlist of HTML `<head>` metadata.
- Preserve manual metadata over source-derived and AI-derived values; support metadata visibility and editing from the management surface.
- Index title, tags, keywords, summary, and content with defined relative retrieval weights and provide tag filtering.
- Make uploads content-addressed within a workspace: identical content is deduplicated, while same-name different-content uploads receive safe unique storage paths rather than overwriting files.
- Treat `<workspace>/uploads` as one fixed persisted upload root; retain upload batch/date/original-path provenance without deriving roots from individual uploaded-file parents.
- Allow startup root declarations through configuration and repeatable CLI options, while keeping management-page root additions/removals dynamic and persistent.
- Use the configured LLM only as a constrained text-generation provider for AI-derived summary, tags, and keywords.

## Capabilities

### New Capabilities

- `document-metadata-search`: Document metadata extraction, provenance, curation, weighted retrieval, and safe upload identity.

### Modified Capabilities

- None.

## Impact

- SQLite schema and repository APIs for documents, metadata, tags, keywords, and upload content identity.
- Ingestion, Org/HTML extraction, LLM metadata generation, Lucene projection, and search HTTP contracts.
- Management WebUI document/metadata and upload interactions.
- Existing workspaces require an additive, backward-compatible schema migration and index rebuild/projection refresh.
