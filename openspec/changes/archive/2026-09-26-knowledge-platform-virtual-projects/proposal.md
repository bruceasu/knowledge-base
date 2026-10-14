## Why

The service has useful document, code-search, symbol, and Agent access capabilities, but its single workspace model does not provide a clean way to combine shared reference material with the code and documents relevant to a particular effort. A small local virtual-project layer lets multiple local Agents share one service and search project-specific context without turning Git repositories into manually registered workspaces or duplicating the existing indexing stack.

## What Changes

- Add virtual projects with metadata stored in the shared knowledge workspace and project-specific indexes stored separately.
- Allow virtual projects to reference multiple local files and directories, including paths outside the service workspace, subject to configured allow/forbid rules where forbid wins.
- Support explicit API-triggered synchronization and scheduled synchronization; do not add a real-time filesystem watcher.
- Search the shared document collection together with an optionally selected virtual project, using deterministic ranking that gives selected-project results additional weight.
- Provide bounded HTTP file operations for project content and Memory files using the same project resource model; extend read-only MCP search additively with a virtual-project selector.
- Reuse existing document, ripgrep, Ctags, and retrieval mechanisms where they fit; keep LLM-generated summaries/metadata and explicit ask answers separate from ordinary ranking.
- Keep the product single-machine with programmatic HTTP/MCP access and no Web UI, LSP, or Codex SDK.
- Retire the superseded `docs/handoffs/plan.md`; use `docs/handoffs/knowledge-platform-requirements.md` as the requirements source for this change.

## Capabilities

### New Capabilities

- `virtual-project-knowledge`: Virtual-project metadata, local resource references, path policy, project indexes, synchronization, search fusion, and Agent file operations.

### Modified Capabilities

- `hybrid-knowledge-retrieval`: Add optional virtual-project retrieval and deterministic project-aware ranking while retaining shared-knowledge search by default.
- `engineering-knowledge-mcp-exposure`: Expose project-scoped search and bounded project file operations to local Agents while preserving the stdio-only transport and path controls.

## Impact

- Affected application areas: virtual-project metadata and resource management, path validation, project index lifecycle, scheduling/synchronization, hybrid retrieval, HTTP API, and the local MCP search schema.
- Existing shared workspace remains the single service/data root; virtual-project source paths may be outside it under explicit path policy.
- Existing workspace-free search behavior remains the default when no virtual project is selected.
- No new dependency, database-wide migration, deployment topology, Web UI, LSP, or Codex SDK is proposed.
- Exact API schemas, path-rule syntax/defaults, supported content extractors, scheduling policy, and ranking weights remain design details; unresolved decisions are marked in `design.md`.
