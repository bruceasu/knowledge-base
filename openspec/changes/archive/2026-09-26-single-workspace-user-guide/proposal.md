## Why

The supported single-workspace product is implemented and verified, but its operational knowledge is spread across README files and focused topic notes. New users need one accurate path from first launch through deployment, daily use, MCP integration, maintenance, and troubleshooting without encountering removed `/api/v1` or logical-workspace instructions.

## What Changes

- Add a task-oriented Chinese user guide covering prerequisites, five-minute startup, configuration, deployment, WebUI, `/api/v2`, local stdio MCP, data layout, backup, upgrade, health checks, shutdown, and troubleshooting.
- Turn the Chinese and English README files into concise entry pages that link to the canonical guide and preserve copyable startup examples.
- Reconcile the existing single-workspace operations note and example TOML with the canonical guide so configuration keys and launch commands have one authoritative meaning.
- Clearly label local-only MCP, the single-workspace process boundary, unsupported legacy options, and security/deployment assumptions.
- Do not change runtime behavior, API schemas, dependencies, persistence formats, authentication, or deployment topology.

## Capabilities

### New Capabilities

- `single-workspace-operator-documentation`: Defines the required quick-start, deployment, usage, maintenance, and troubleshooting documentation for the supported product.

### Modified Capabilities

None.

## Impact

Changes are limited to Markdown documentation, the example configuration when clarification is required, and OpenSpec artifacts. Production Java, WebUI behavior, APIs, schemas, dependencies, user data, and archived history are unaffected.
