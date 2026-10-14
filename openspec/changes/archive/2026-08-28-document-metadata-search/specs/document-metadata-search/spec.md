## ADDED Requirements

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

### Requirement: Workspace roots can be declared and managed dynamically
The system MUST persist root lifecycle information in each workspace. It MUST allow local operators to add roots by syncing directories and to detach or explicitly purge them through the management API. It MUST support repeatable `--server-root <workspaceId>=<path>` startup declarations and equivalent `server_roots` configuration entries.

#### Scenario: Startup declares multiple roots for one workspace
- **WHEN** configuration or CLI supplies multiple root declarations for one logical workspace
- **THEN** the server synchronizes each declared directory through that workspace service and the roots remain present after restart

#### Scenario: Operator dynamically removes a root
- **WHEN** an operator detaches a root from the management page
- **THEN** the root lifecycle is persisted as detached and other roots in the workspace remain active

### Requirement: Metadata is observable and editable through management
The management surface MUST show a document's effective metadata and provenance and MUST allow authorized local users to edit title, tags, keywords, and summary as manual metadata. A successful edit MUST reproject the affected document without changing its document identity.

#### Scenario: Operator edits document tags
- **WHEN** an operator saves manual tags for a document
- **THEN** subsequent filtered and unfiltered searches observe the updated effective tags
