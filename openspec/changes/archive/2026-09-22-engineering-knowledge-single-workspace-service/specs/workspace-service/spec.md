## MODIFIED Requirements

### Requirement: Server owns workspace resources
The system MUST provide one server process that owns exactly one workspace context. That context MUST own its mutable SQLite and Lucene resources and MUST be identified by the configured normalized root only within trusted status/configuration output; clients MUST NOT select it through a logical workspace ID.

#### Scenario: All operations use the service workspace
- **WHEN** a valid read or write operation is accepted
- **THEN** it uses the one service-owned workspace context without registry lookup or caller-selected routing

### Requirement: Stable workspace and status fields
The system MUST expose overall service state and the current workspace's lifecycle state, reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time. It MUST NOT expose a selectable workspace inventory or require a logical workspace ID.

#### Scenario: Status reports projection lag
- **WHEN** business progress exceeds available projection progress
- **THEN** status reports the current workspace as degraded with its freshness fields and no alternate-workspace inventory

## REMOVED Requirements

### Requirement: Workspace failures are isolated
**Reason**: The new service owns only one workspace, so cross-workspace failure isolation does not exist.
**Migration**: Deploy independent service processes when independent failure domains are required.

### Requirement: Workspace lifecycle is isolated
**Reason**: Registration and independent lifecycle of several workspaces are removed.
**Migration**: Restart or repair the single configured service workspace.

### Requirement: Workspace lifecycle does not block unrelated workspaces
**Reason**: There are no unrelated workspaces inside one service process.
**Migration**: Use separate service instances for unrelated roots.

