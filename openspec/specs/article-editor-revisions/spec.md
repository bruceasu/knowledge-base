# article-editor-revisions Specification

## Purpose
TBD - created by archiving change article-editor-revisions. Update Purpose after archive.
## Requirements
### Requirement: Articles have stable identities and current revisions

The system MUST represent a logical Article with a stable identity and a current revision. Each content save MUST create an immutable revision, and Search/Ask MUST use only the current revision.

#### Scenario: Content save creates a new current revision

- **WHEN** a user saves changed text for an Article with a matching base hash
- **THEN** the system creates a new immutable revision, makes it current, and leaves the previous revision available in history

#### Scenario: Historical revisions are excluded from retrieval

- **WHEN** an Article has current and historical revisions
- **THEN** Search and Ask return content from only the current revision

### Requirement: Text documents can be viewed and explicitly edited

The system MUST allow a user to view the full text and Article-level metadata for a supported text document. The system MUST keep viewing separate from editing, and MUST require an explicit edit/save action before changing source content.

#### Scenario: Reference opens document view

- **WHEN** a user selects a Search or Ask reference
- **THEN** the system opens the referenced Article in view mode with its full current text, stable identity, source path, metadata, and content hash

#### Scenario: User saves an edited root document

- **WHEN** a user explicitly saves changed text for a document in a registered root
- **THEN** the system writes the new current content back to that source file and creates a revision without creating a competing `articles` copy

### Requirement: New and uploaded text content can become Articles

The system MUST allow a user to create a new text Article under the workspace `articles` area organized by date. Existing upload behavior MUST remain supported under `uploads`, and saved new or uploaded Articles MUST become searchable after their current revision is projected.

#### Scenario: User creates a new Article

- **WHEN** a user saves a new supported text Article
- **THEN** the system creates a stable Article, stores its current file under the workspace articles area, and makes it eligible for Search and Ask after projection

#### Scenario: Existing upload remains an independent Article source

- **WHEN** a user uploads a supported text file through the existing upload flow
- **THEN** the system preserves the upload behavior and associates the resulting content with a stable Article without requiring the upload to pass through the editor

### Requirement: Concurrent edits are protected

The system MUST compare the submitted base hash with the current source hash before saving. It MUST attempt automatic merging only for non-overlapping line changes in plain text and MUST reject overlapping conflicts without silently overwriting either version.

#### Scenario: Server content is unchanged

- **WHEN** the submitted base hash matches the current source hash
- **THEN** the system saves the submitted content as a new revision

#### Scenario: Non-overlapping text changes can merge

- **WHEN** the source changed after the document was opened and the submitted plain-text changes do not overlap the server changes
- **THEN** the system creates a merged new revision and reports that the merge succeeded

#### Scenario: Overlapping changes produce a conflict

- **WHEN** the source changed after the document was opened and the submitted plain-text changes overlap the server changes
- **THEN** the system rejects the save and returns enough base/server/submitted information for the user to inspect the difference

### Requirement: History can be inspected and restored safely

The system MUST allow a user to inspect an Article's historical revisions and restore a selected revision by creating a new current revision. Historical revision files MUST be stored in workspace-managed history outside normal Search/Ask indexing.

#### Scenario: User views revision history

- **WHEN** a user opens an Article's history
- **THEN** the system lists historical revisions with their creation time, content hash, and source information without treating them as searchable documents

#### Scenario: User restores a historical revision

- **WHEN** a user restores a selected historical revision
- **THEN** the system copies its content into a new revision, makes that new revision current, and preserves the selected historical revision unchanged

### Requirement: Deleted content uses a recoverable trash lifecycle

The system MUST move deleted Article files and revision material into a workspace-managed trash retained for 30 days. Deleted Articles MUST be excluded from Search and Ask immediately. Trash cleanup MUST preserve current revisions for live Articles and MUST support retry after partial failure.

#### Scenario: Root document is deleted

- **WHEN** a user confirms deletion of a document from a registered root
- **THEN** the system moves the source file to workspace trash, removes it from active retrieval, and leaves no tombstone in the root

#### Scenario: Article is restored from trash

- **WHEN** a user restores a deleted managed Article before retention expiry
- **THEN** the system restores the Article, its current content, and its history without silently overwriting an existing target file

#### Scenario: Purge has partial file failures

- **WHEN** a root purge can move some files but cannot access or move others
- **THEN** the system reports partial success, leaves failed files at their original paths, and allows the purge to be retried

### Requirement: File save and index projection statuses are distinct

The system MUST report file persistence separately from Search/Ask projection. A successful file save MUST remain recoverable if projection is delayed or fails, and pending projection MUST be retried using workspace recovery behavior.

#### Scenario: File save precedes projection

- **WHEN** the source file is saved but the index projection has not completed
- **THEN** the system reports the file as saved and the index as pending rather than claiming that both are complete

#### Scenario: Projection failure is recoverable

- **WHEN** indexing the current revision fails after file persistence
- **THEN** the system retains the current Article state, reports the projection failure, and makes the work eligible for retry or rebuild

