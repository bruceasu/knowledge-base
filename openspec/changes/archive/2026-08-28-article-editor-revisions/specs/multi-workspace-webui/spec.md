## ADDED Requirements

### Requirement: References open within their source workspace

The WebUI MUST include the source workspace and stable Article/document identity in Search and Ask references. Selecting a reference MUST open the document using that workspace context, and subsequent view, edit, history, restore, delete, and save operations MUST target the same workspace captured when the action began.

#### Scenario: Search reference opens in its workspace

- **WHEN** a Search result belongs to the selected workspace and the user opens it
- **THEN** the WebUI requests the document using that workspace ID and stable document identity

#### Scenario: Ask source remains addressable

- **WHEN** an Ask response contains a source reference
- **THEN** the source includes enough stable identity and workspace context for the WebUI to open the same Article without resolving by display title or path alone

#### Scenario: Workspace changes during an edit

- **WHEN** the user changes the selected workspace after opening an Article but before saving
- **THEN** the in-flight save remains targeted at the workspace captured when editing began and MUST NOT be redirected to the newly selected workspace
