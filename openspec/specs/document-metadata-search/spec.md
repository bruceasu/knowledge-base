# document-metadata-search Specification

## Purpose
TBD - created by archiving change document-metadata-search. Update Purpose after archive.
## Requirements
### Requirement: Paged document browsing uses one read snapshot

The optional paged document-list request MUST obtain its `total` and page rows from one isolated SQLite read transaction. This read transaction MUST NOT share the workspace FIFO write connection, and it MUST preserve literal path-query matching and stable `path, id` ordering.

#### Scenario: A write is in progress while a page is read

- GIVEN a document replacement transaction has not committed
- WHEN a paged document-list request is served
- THEN its total and rows reflect one committed SQLite snapshot and do not contain intermediate replacement state

### Requirement: Source-native document metadata is extracted
The system MUST extract document metadata during ingestion. For Org files it MUST recognize `#+TITLE` and `#+FILETAGS`. For HTML files it MUST use only the `<head>` allowlist: title/`og:title`, description/`og:description`, `keywords`, and `article:tag`. It MUST NOT treat arbitrary Org properties or arbitrary HTML meta names as document metadata.

#### Scenario: Org file provides file tags
- **WHEN** an Org document contains `#+TITLE` and `#+FILETAGS`
- **THEN** the system stores those values as source-derived title and tags

#### Scenario: HTML document provides allowed head metadata
- **WHEN** an HTML document contains an allowed keywords or article tag metadata element
- **THEN** the system stores normalized values as source-derived keywords or tags

### Requirement: Metadata provenance and manual curation are preserved
The system MUST retain provenance for title, summary, tags, and keywords and MUST preserve manual metadata when source extraction or AI generation runs again. Effective metadata MUST prefer manual values over source-derived values and source-derived values over AI-derived values.

#### Scenario: Sync refreshes an AI-derived tag after manual curation
- **WHEN** a document has a manual tag and a later sync regenerates AI metadata
- **THEN** the manual tag remains effective and is not removed by the sync

### Requirement: AI-derived metadata is bounded and validated
The system MUST treat LLM output as untrusted text, validate the allowed title, summary, tag, and keyword values before persistence, and record generation provenance. Invalid or failed AI output MUST NOT remove valid source-derived or manual metadata.

#### Scenario: AI metadata output is malformed
- **WHEN** metadata generation returns malformed output
- **THEN** the document remains searchable with its existing deterministic metadata

### Requirement: Metadata participates in weighted retrieval and tag filtering
The system MUST project effective title, tags, keywords, summary, and content into the document search projection. It MUST give title, tags, keywords, summary, and content relative retrieval boosts of 4, 3, 2, 1.5, and 1 respectively. It MUST support exact normalized tag filtering without changing the behavior of a search that omits filters.

#### Scenario: Tag filter narrows search results
- **WHEN** a caller searches with a normalized tag filter
- **THEN** every returned result contains that effective tag

#### Scenario: Title match outranks equivalent content-only match
- **WHEN** the same query term occurs in one document title and only in another document body
- **THEN** the title match receives the higher metadata contribution to ranking

### Requirement: Uploads are collision-safe and content-deduplicated
The system MUST identify uploads by SHA-256 within a workspace. It MUST reuse an existing document for identical uploaded content and MUST NOT overwrite an existing upload merely because filenames or relative paths collide. Different content with the same filename MUST receive a distinct stable storage path with normalized display naming. All accepted browser uploads MUST belong to the single fixed `<workspace>/uploads` root; original filename, relative path, upload batch, and received time are provenance rather than root identity.

#### Scenario: Identical content is uploaded twice
- **WHEN** a user uploads content whose SHA-256 already exists in the selected workspace
- **THEN** the response identifies the operation as deduplicated and returns the existing document identity without creating another physical copy

#### Scenario: Different same-name content is uploaded
- **WHEN** a user uploads a file whose name matches an existing upload but whose SHA-256 differs
- **THEN** the system stores and indexes a new file without replacing the existing upload

#### Scenario: Directory upload has one fixed root
- **WHEN** a user uploads a directory containing nested files
- **THEN** each accepted document is attributed to `<workspace>/uploads` rather than to its individual parent directory

### Requirement: Metadata is observable and editable through management

The management and document editing surfaces MUST show a document's effective metadata and provenance and MUST allow authorized local users to edit title, tags, keywords, and summary as manual metadata. A document editing surface MUST also allow supported text content to be viewed and explicitly edited. A successful metadata-only edit MUST reproject the affected current document without changing its Article identity or content revision. A successful content edit MUST create a new current revision while preserving manual metadata.

#### Scenario: Operator edits document tags

- **WHEN** an operator saves manual tags for a document
- **THEN** subsequent filtered and unfiltered searches observe the updated effective tags and the document identity remains stable

#### Scenario: User edits document content

- **WHEN** a user saves changed supported text content with a matching base hash
- **THEN** the system creates a new current revision, preserves manual metadata, and reprojects the current document

#### Scenario: Content revision inherits Article metadata

- **WHEN** a document content edit creates a new current revision
- **THEN** the effective title, summary, keywords, and tags remain the Article's effective metadata unless the user explicitly changes metadata

### Requirement: Search resource reuse preserves committed visibility

The system MUST reuse available committed search resources across queries and refresh them after successful publication. Resource reuse and metadata batching MUST preserve ranking, tag filtering, manual metadata precedence, and workspace isolation. In-flight searches MUST retain their acquired snapshot until completion.

#### Scenario: Search overlaps a successful index update
- **WHEN** a search holds the previous snapshot while a new commit is published
- **THEN** that search can finish safely and subsequent searches can use the new committed snapshot

#### Scenario: Metadata changes on a multi-chunk document
- **WHEN** manual metadata is updated and successfully projected
- **THEN** all current chunks use the updated effective metadata without cross-workspace cache contamination

### Requirement: Incremental sync avoids unchanged content preparation

The system MUST skip decoding, chunking, and model calls for a source whose hash matches complete committed business state, while still recovering previously pending projection batches. Changed content MUST preserve existing decoding, metadata, Article identity, and current-revision behavior.

#### Scenario: An unchanged source has pending projection
- **WHEN** sync encounters unchanged committed content with an outstanding projection batch
- **THEN** it completes projection recovery without repeating content preparation or creating duplicate business records

#### Scenario: The source changes
- **WHEN** its current content hash differs from complete committed state
- **THEN** sync prepares and commits the changed content with the existing metadata precedence and revision semantics

### Requirement: Workspace roots are managed within the service workspace

The system MUST persist root lifecycle information inside the one configured service workspace.
Local operators MAY add roots by syncing directories and MAY detach or explicitly purge them
through the workspace-free management API. The system MUST NOT accept logical workspace IDs,
repeatable `--server-root <workspaceId>=<path>` startup declarations, or `server_roots`
configuration entries.

#### Scenario: Operator adds multiple roots

- **WHEN** a local operator syncs multiple allowed directories through the service API
- **THEN** each root lifecycle is persisted inside the configured service workspace without registering or selecting a logical workspace

#### Scenario: Operator dynamically removes a root

- **WHEN** an operator detaches one root from the management page
- **THEN** that root is persisted as detached and other roots in the service workspace remain active

### Requirement: Document browsing uses bounded pages

The document list API MUST accept optional limit, offset, and query parameters. A request MUST use
limit between 1 and 200 and a nonnegative offset, defaulting to limit 50 and offset 0 when omitted.
query MUST perform a case-insensitive literal substring match on document paths, treating wildcard
characters literally and a blank query as no filter. Results MUST use stable path then ID ordering
and include documents, total, limit, and offset. Invalid paging values MUST return the supported
workspace-free 400 error envelope. The API MUST NOT preserve a special unpaged legacy response.

#### Scenario: A caller lists documents without paging arguments

- **WHEN** a request omits limit, offset, and query
- **THEN** the API returns the default bounded page with total, limit, and offset

#### Scenario: A user retrieves another page

- **WHEN** a request supplies limit 50 and offset 50
- **THEN** it returns at most 50 matching documents after the first 50 in stable order, with a total from the same read snapshot

#### Scenario: A user searches paths containing wildcard characters

- **WHEN** query contains percent or underscore characters
- **THEN** those characters match themselves rather than expanding as SQL wildcards

#### Scenario: Paging values are invalid

- **WHEN** limit is zero or above 200, offset is negative, or a supplied numeric value is malformed
- **THEN** the API returns 400 without executing an unbounded fallback query

### Requirement: Management document browsing is incremental and single-workspace

The management UI MUST use paged document browsing with path filtering, explicit page navigation,
and an empty result state for the configured service workspace. Changing the filter MUST reset
paging and selection; stale responses MUST NOT overwrite the newer query. The UI MUST NOT expose
a workspace selector.

#### Scenario: Filter changes before a list request completes

- **WHEN** a response for an older filter arrives after the user submits a newer filter
- **THEN** the UI ignores the stale response and keeps the newer document list and selection state

#### Scenario: No document matches a filter

- **WHEN** the service workspace has no matching document
- **THEN** the UI presents an empty state without retaining the previously selected document for editing

