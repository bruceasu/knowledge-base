## 1. WebUI state and feedback foundation

- [x] 1.1 Inventory current async actions in `app.js` and `manage.js`, then introduce a shared local pattern for loading, success, partial/unavailable, error, retry, and stale-request handling without adding a frontend dependency.
- [x] 1.2 Update the usage page to disable duplicate submissions, show actionable empty/error states, and preserve safe server diagnostics in activity and result areas.
- [x] 1.3 Update the management page to show operation progress, readiness/degraded state, retry actions, and clear confirmation boundaries for destructive operations.

## 2. WebUI result and virtual-project experience

- [x] 2.1 Improve search, ask, source, and knowledge-object rendering so scope, virtual project, freshness, evidence, relative path, line range, incomplete, and truncation states are visible.
- [x] 2.2 Add management-page virtual project listing/selection, resource visibility, explicit sync, and sync-status refresh using existing `/api/v2/virtual-projects` routes; do not expose source-file deletion by default.
- [x] 2.3 Improve keyboard focus, accessible labels, narrow-screen layout, and notification semantics for the newly exposed flows.
- [x] 2.4 Keep every changed source asset byte-identical to its matching `src/main/resources/webui/` asset.

## 3. MCP descriptions and diagnostics

- [x] 3.1 Add a centralized per-tool description catalog for `knowledge_search`, `knowledge_get`, `code_symbol`, `code_search`, `trace`, and `build_context`, including intended use, key inputs, result limits, and tool relationships.
- [x] 3.2 Wire the descriptions into MCP tool registration while preserving stdio-only transport, read-only annotations, schema version `1.0`, and existing input/output fields.
- [x] 3.3 Review controlled diagnostic codes/details for invalid arguments, not-found, unavailable, incomplete, timeout, cancellation, and internal outcomes; make recovery guidance actionable without exposing sensitive internals.

## 4. Tests and documentation

- [x] 4.1 Extend WebUI contract coverage for new endpoints, state/result markers, accessibility hooks, and source/bundled asset parity.
- [x] 4.2 Extend MCP tests for distinct tool descriptions, compatibility of the six schemas, sanitized actionable diagnostics, result truncation, and read-only annotations.
- [x] 4.3 Update README and user/deployment documentation with the improved WebUI workflow and MCP tool guidance, keeping all examples aligned with current code.
- [ ] 4.4 Run the narrowest approved static checks and focused tests; record TODO, Verification, limitations, and any remaining `%%` notes.

## TODO

- [x] A user can understand and recover from every primary WebUI operation state.
- [x] Search and project results expose enough provenance and freshness information for informed use.
- [x] An MCP client can choose and recover from each tool using its metadata and diagnostics without protocol guessing.

## Verification

- [x] WebUI source and bundled resources remain synchronized.
- [x] Existing HTTP and MCP contracts remain backward compatible by static inspection; focused runtime tests remain pending.
- [x] No new dependency, network transport, write-capable MCP operation, or deployment boundary is introduced.

%% Focused Maven tests were not run in this turn because repository execution policy does not authorize runtime tests by default.
