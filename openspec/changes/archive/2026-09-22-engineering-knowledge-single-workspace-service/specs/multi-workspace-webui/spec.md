## ADDED Requirements

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

## REMOVED Requirements

### Requirement: WebUI has explicit current workspace selection
**Reason**: The new service owns exactly one workspace.
**Migration**: Remove saved workspace selection and use the service instance URL as the workspace boundary.

### Requirement: Search falls back only after current-workspace zero hits
**Reason**: Cross-workspace fallback is not part of the single-workspace product.
**Migration**: Query another independently deployed service explicitly if needed.

### Requirement: Management upload and sync target the selected workspace
**Reason**: Upload and sync always target the service workspace.
**Migration**: Remove workspace capture from UI requests.

### Requirement: Management uses existing workspace lifecycle contracts
**Reason**: Registration, unregistration, and per-workspace repair endpoints are removed.
**Migration**: Use service-level readiness and maintenance operations.

### Requirement: Existing API clients remain compatible
**Reason**: This is a breaking new implementation and compatibility is not required.
**Migration**: Update clients to the new workspace-free API.

### Requirement: References open within their source workspace
**Reason**: References cannot cross workspaces in the new service.
**Migration**: Retain stable content identity and relative location only.

