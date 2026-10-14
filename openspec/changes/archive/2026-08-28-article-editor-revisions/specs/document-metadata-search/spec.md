## MODIFIED Requirements

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
