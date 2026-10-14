# Multi-workspace WebUI

## ADDED Requirements

### Requirement: WebUI has explicit current workspace selection

The WebUI MUST load workspace inventory from the server status response, present a selected logical workspace ID, and validate the selection against the latest inventory. It MUST send that ID on every workspace-scoped use request.

#### Scenario: User chooses a ready workspace

- GIVEN the status response contains ready workspaces `alpha` and `beta`
- WHEN the user chooses `beta`
- THEN subsequent search, ask, upload, and sync requests include `workspaceId=beta`
- AND the UI identifies `beta` as the current workspace

#### Scenario: Selected workspace is no longer available

- GIVEN the browser has selected workspace `beta`
- WHEN a refreshed status response no longer contains `beta`
- THEN the UI clears or replaces the selection with an available workspace
- AND it MUST NOT submit new use requests with the stale ID

### Requirement: Search falls back only after current-workspace zero hits

The WebUI MUST search the selected workspace first. It MUST attempt other ready workspaces only when that response successfully contains zero hits. It MUST stop after the first non-empty fallback result and MUST identify the result workspace.

#### Scenario: Current workspace contains a hit

- GIVEN `alpha` is selected and a search in `alpha` returns one or more hits
- WHEN the user performs the search
- THEN the UI does not request the same query from another workspace
- AND it presents `alpha` as the result workspace

#### Scenario: Fallback workspace contains a hit

- GIVEN `alpha` is selected, its search returns zero hits, and `beta` is ready
- WHEN the search in `beta` returns hits
- THEN the UI presents those hits as sourced from `beta`
- AND it indicates that the result came from fallback search

#### Scenario: Current workspace search fails

- GIVEN `alpha` is selected
- WHEN its search returns an HTTP or contract error
- THEN the UI presents the error
- AND it MUST NOT interpret the failure as zero hits or silently search another workspace

### Requirement: Management upload and sync target the selected workspace

The management page MUST capture the current workspace ID when a user starts upload or directory sync and MUST include it in the request. A later selection change MUST NOT change the target of that in-flight operation.

#### Scenario: Upload targets the selected workspace

- GIVEN `alpha` is the selected workspace
- WHEN the user uploads a file
- THEN the upload request includes `workspaceId=alpha`
- AND the UI reports the operation against `alpha`

### Requirement: Use and management have separate pages

The bundled WebUI MUST expose a use page and a management page below `/webui`. The use page MUST contain search, ask, and session interactions; workspace lifecycle and ingestion controls MUST reside on the management page.

#### Scenario: User opens the root URL

- WHEN a user requests `/`
- THEN the server redirects to the bundled use page at `/webui/index.html`

#### Scenario: Operator opens management

- WHEN an operator requests the management page
- THEN the page fetches status and presents server state and per-workspace lifecycle information

### Requirement: Management uses existing workspace lifecycle contracts

The management page MUST use the status, registration, unregistration, and repair endpoints without changing their success, error, or default-workspace protections.

#### Scenario: Operator repairs a failed workspace

- GIVEN status reports a workspace as failed or degraded
- WHEN the operator invokes repair
- THEN the UI sends the existing repair request for that logical workspace ID
- AND refreshes status after receiving the synchronous response

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

### Requirement: Existing API clients remain compatible

The service MUST preserve existing default-workspace behavior for API clients that omit `workspaceId`. The WebUI MUST nevertheless include `workspaceId` for every workspace-scoped request.

#### Scenario: Existing client omits workspace ID

- GIVEN an existing client calls search without `workspaceId`
- WHEN the request is accepted by the service
- THEN the service uses its established default-workspace behavior
