## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Workspace roots can be declared and managed dynamically
**Reason**: Logical workspace startup declarations and selection are removed; roots now belong to the one configured service workspace.
**Migration**: Sync, detach, or purge roots through the workspace-free service API.

### Requirement: Document browsing supports compatible bounded pages
**Reason**: The legacy unpaged response is removed; all document browsing now uses the bounded workspace-free response.
**Migration**: Consume the paged response with `documents`, `total`, `limit`, and `offset`.

### Requirement: Management document browsing is incremental and workspace scoped
**Reason**: The management UI no longer selects logical workspaces.
**Migration**: Browse and filter documents in the configured service workspace.
