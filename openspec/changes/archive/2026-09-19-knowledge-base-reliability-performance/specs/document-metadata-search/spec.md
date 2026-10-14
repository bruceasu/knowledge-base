## ADDED Requirements

### Requirement: Document browsing supports compatible bounded pages

The document list API MUST accept optional limit, offset, and query parameters. A paged request MUST use limit between 1 and 200 and a nonnegative offset, defaulting to limit 50 and offset 0 when omitted. query MUST perform a case-insensitive literal substring match on document paths, treating wildcard characters literally and a blank query as no filter. Results MUST use stable path then ID ordering and include documents, total, limit, and offset. Requests with none of these parameters MUST retain the existing unpaged response behavior. Invalid paging values MUST return the established 400 error envelope.

#### Scenario: A legacy client lists documents
- **WHEN** a request omits limit, offset, and query
- **THEN** the API retains the existing document-list behavior and response fields

#### Scenario: A user retrieves another page
- **WHEN** a request supplies limit 50 and offset 50
- **THEN** it returns at most 50 matching documents after the first 50 in stable order, with a total from the same read snapshot

#### Scenario: A user searches paths containing wildcard characters
- **WHEN** query contains percent or underscore characters
- **THEN** those characters match themselves rather than expanding as SQL wildcards

#### Scenario: Paging values are invalid
- **WHEN** limit is zero or above 200, offset is negative, or a supplied numeric value is malformed
- **THEN** the API returns 400 without executing an unbounded fallback query

### Requirement: Management document browsing is incremental and workspace scoped

The management UI MUST use paged document browsing with path filtering, explicit page navigation, and an empty result state. Changing workspace or filter MUST reset paging and selection; stale responses MUST NOT overwrite the newly selected workspace or query.

#### Scenario: Workspace changes before a list request completes
- **WHEN** a response for workspace A arrives after the user selects B
- **THEN** the UI ignores A's stale response and keeps B's document list and selection

#### Scenario: No document matches a filter
- **WHEN** the selected workspace has no matching document
- **THEN** the UI presents an empty state without retaining the previously selected document for editing

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
