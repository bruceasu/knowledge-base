# multi-workspace-webui Specification

## Purpose
TBD - created by archiving change multi-workspace-webui. Update Purpose after archive.
## Requirements
### Requirement: Use and management have separate pages

The bundled WebUI MUST expose a use page and a management page below `/webui`. The use page MUST contain search, ask, and session interactions; workspace lifecycle and ingestion controls MUST reside on the management page.

#### Scenario: User opens the root URL

- WHEN a user requests `/`
- THEN the server redirects to the bundled use page at `/webui/index.html`

#### Scenario: Operator opens management

- WHEN an operator requests the management page
- THEN the page fetches status and presents server state and per-workspace lifecycle information

### Requirement: HTTP server publishes bundled WebUI resources

When no explicit web UI directory override is configured, the HTTP server MUST serve the WebUI assets packaged with the application under `/webui`. It MUST continue to redirect `/` to `/webui/index.html`.

#### Scenario: Packaged server starts without an external webui directory

- GIVEN the application runs from its packaged artifact without a configured `webui-dir`
- WHEN a user requests `/webui/index.html`
- THEN the server returns the bundled use page successfully

#### Scenario: Explicit development override is configured

- GIVEN a valid explicit `webui-dir` contains replacement assets
- WHEN the server starts
- THEN `/webui` serves the configured assets instead of bundled assets

### Requirement: WebUI targets the service workspace implicitly
The bundled WebUI MUST perform supported use and management operations against the service-owned workspace without displaying or persisting a workspace selector and without sending `workspaceId`.

#### Scenario: Load the use page
- **WHEN** a user opens the bundled use page on a ready service
- **THEN** search, ask, sessions, and references target the current service workspace without workspace selection

### Requirement: Management retains single-workspace operations
The management page MUST retain supported status, ingestion, document metadata, root/index, and maintenance operations while removing workspace registration, unregistration, repair selection, and workspace inventory controls.

#### Scenario: Upload content
- **WHEN** a user uploads a supported file from the management page
- **THEN** the request targets the service workspace without a workspace field and reports the resulting ingestion/projection outcome

### Requirement: References remain stable without workspace context
Search and Ask references MUST contain stable object or document identity and workspace-relative location sufficient to open the referenced content in the same service instance.

#### Scenario: Open a search reference
- **WHEN** the user selects a result reference
- **THEN** the UI opens the referenced content by stable identity without resolving or transmitting a workspace ID

