# Engineering transport retirement

## Purpose
FD-001 retires the former local MCP transport. Prior MCP-only requirements are superseded; historical change artifacts remain as history.

## Requirements
### Requirement: HTTP-only product entry
The product SHALL NOT provide an MCP launcher, tool registration or MCP SDK dependency. Personal secretary clients SHALL use the versioned HTTP contract in `personal-knowledge-service` and SHALL NOT open the service workspace.

#### Scenario: Upgrade an MCP client
- WHEN an operator upgrades to this version
- THEN the former MCP main class is absent and the migration guide directs personal knowledge clients to the secretary HTTP contract.

### Requirement: Preserve shared application capabilities
Removing MCP SHALL NOT remove KnowledgeAgentFacade, retrieval, controlled reads, evidence trace or context building used by HTTP. Existing HTTP response contracts and application sanitization/budget boundaries SHALL remain compatible.

#### Scenario: Shared code remains in use
- WHEN a former MCP dependency is also used by HTTP
- THEN only the MCP-specific adapter is removed.