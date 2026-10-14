## ADDED Requirements

### Requirement: Writes are serialized for the service workspace
The system MUST route every service-workspace mutation through its bounded FIFO write coordination and MUST NOT retain a registry of independent workspace queues.

#### Scenario: Concurrent writes preserve request order
- **WHEN** two writes are accepted for the service workspace while the first is still running
- **THEN** the second write does not mutate the workspace before the first write completes

## MODIFIED Requirements

### Requirement: Workspace status is observable
The system MUST expose enough health/status information to distinguish process liveness, complete-composition readiness, and the service workspace state, including ready/degraded/failed state, bounded reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time. It MUST NOT expose a selectable workspace inventory.

#### Scenario: Projection is degraded
- **WHEN** the service workspace has a storage or projection recovery problem
- **THEN** service status identifies the degraded component and reason without listing alternate workspaces

### Requirement: Stable workspace and status fields
The system MUST expose overall service state and the current workspace's lifecycle state, reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time. It MUST NOT expose a logical workspace ID, display-name registry, selectable inventory, or normalized absolute root in an unauthenticated readiness response.

#### Scenario: Status distinguishes liveness and readiness
- **WHEN** the composition is alive but projection progress is degraded
- **THEN** health remains live, readiness is unavailable, and status reports the current workspace freshness without another workspace identity

## REMOVED Requirements

### Requirement: Writes are serialized per workspace
**Reason**: The logical workspace registry and its independent queues are removed; one FIFO coordinates the configured service workspace.
**Migration**: Submit mutations to the single-workspace service without selecting a logical workspace.

### Requirement: CLI content operations use the service
**Reason**: The legacy CLI and its `/api/v1` HTTP client are removed; the supported product surfaces are the single-workspace HTTP/WebUI service and read-only stdio MCP.
**Migration**: Use `/api/v2`, the bundled WebUI, or the explicit MCP launcher as appropriate.

### Requirement: Service discovery cannot fall back to direct mutation
**Reason**: The legacy content CLI and service-discovery fallback path are removed rather than retained as a second mutation surface.
**Migration**: Start the configured single-workspace service and call `/api/v2`; there is no legacy CLI fallback.

### Requirement: Stable synchronous outcomes
**Reason**: This requirement defines the removed `/api/v1` error envelope containing `workspaceId`; supported v2 operations use their application result and workspace-free error contracts.
**Migration**: Consume the `/api/v2` workspace-free outcome and error schemas.
