# single-workspace-operator-documentation Specification

## Purpose
TBD - created by archiving change single-workspace-user-guide. Update Purpose after archive.
## Requirements
### Requirement: Quick start is complete and copyable
The repository MUST provide a canonical quick-start path that states prerequisites, prepares configuration, builds the executable artifact, starts the single-workspace HTTP service, opens the bundled WebUI, and verifies health and readiness without using removed commands or implicit workspace defaults.

#### Scenario: New user starts the service
- **WHEN** a user follows the quick-start instructions with a writable workspace root and valid local toolchain
- **THEN** the documented commands lead to the `/api/v2` service and bundled WebUI and identify how to verify both liveness and readiness

### Requirement: Deployment boundaries are explicit
The operations guide MUST document the supported foreground Java process, required filesystem ownership, configuration handling, health probes, graceful shutdown, backup unit, upgrade sequence, and access-boundary assumptions. It MUST NOT present built-in authentication, TLS, remote MCP, clustering, containers, or a particular service manager as supported product behavior unless the repository implements them.

#### Scenario: Operator prepares a managed deployment
- **WHEN** an operator adapts the foreground command to a process supervisor or reverse proxy
- **THEN** the guide identifies the stable process command, probe endpoints, persistent workspace path, and security responsibilities without inventing deployment topology

### Requirement: Usage documentation covers supported surfaces
The guide MUST explain the bundled WebUI, representative workspace-free `/api/v2` operations, and the six read-only local stdio MCP tools. It MUST identify the explicit MCP main class and MUST NOT advertise `/api/v1`, logical workspace registration/selection, or network MCP.

#### Scenario: User chooses an interaction surface
- **WHEN** a user needs browser, HTTP automation, or local agent access
- **THEN** the guide directs them respectively to WebUI, `/api/v2`, or the explicit stdio MCP launcher with the relevant constraints

### Requirement: Configuration and troubleshooting match production behavior
The guide MUST list canonical configuration keys and CLI precedence, identify obsolete workspace keys as fatal, explain important workspace data directories, and provide bounded troubleshooting steps for startup, readiness, ports, optional tools, LLM configuration, and workspace state.

#### Scenario: Startup configuration is invalid
- **WHEN** startup rejects a missing, duplicate, malformed, or obsolete workspace option
- **THEN** the guide directs the operator to exactly one `workspace_root`/`--workspace-root` and explains that CLI values override matching TOML values

### Requirement: Documentation entry points remain coherent
The English and Chinese README files MUST link to the canonical operations material and MUST keep their minimum launch examples consistent with the supported manifest main and explicit MCP launcher. Focused topic documents MAY remain separate but MUST NOT contradict the canonical guide.

#### Scenario: User enters through either README
- **WHEN** a user starts from the English or Chinese repository landing page
- **THEN** they can find the quick start, deployment guide, configuration example, and supported service surfaces without encountering contradictory legacy instructions

