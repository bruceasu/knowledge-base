## Why

Search and Ask already identify reference documents, but the WebUI cannot take the user from a reference to the document content and a controlled edit flow. The knowledge base is primarily backed by user-managed root folders, so online editing must remain an auxiliary path that protects local edits, preserves history, and keeps Search/Ask aligned with the current document version.

## What Changes

- Add a document reading surface reachable from Search and Ask references.
- Add an explicit edit mode for text documents with title, summary, keywords, tags, and content editing.
- Allow WebUI editing of text files in all registered root folders, plus creation of managed articles under workspace `articles` and existing upload content under workspace `uploads`.
- Give each logical Article a stable identity and maintain immutable content revisions.
- Detect concurrent local edits with a base hash; auto-merge only non-overlapping line-level changes in plain text and show conflicts otherwise.
- Store history and deleted content in workspace-managed areas that are excluded from normal Search/Ask indexing.
- Move deleted files to a 30-day trash area, with restoration and retryable partial failure handling for root purge.
- Make only the current revision searchable and expose separate file-save and index-projection status.

## Capabilities

### New Capabilities

- `article-editor-revisions`: View, create, edit, version, restore, delete, and index text Articles while protecting root-folder content and concurrent edits.

### Modified Capabilities

- `document-metadata-search`: Extend document management from metadata-only editing to current-content editing and revision-aware projection while retaining metadata ownership and precedence rules.
- `multi-workspace-webui`: Allow Search/Ask references to open a document view/editor while preserving the selected workspace for every document operation.

## Impact

- Affects the HTTP document contract, Search/Ask reference payloads, WebUI use and management flows, document persistence, file operations, ingestion, and Lucene projection.
- Requires durable tracking for Article identity, current revision, revision history, trash lifecycle, base hashes, and projection status.
- Requires safe file writes and recovery behavior across multiple registered root folders.
- No new external dependency or framework is required by this proposal.
