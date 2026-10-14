## Context

The server already owns multiple isolated workspace contexts. Its HTTP boundary accepts `workspaceId` for workspace-scoped operations, exposes server/workspace status, and publishes external static files at `/webui`. The existing WebUI does not retain or send a selected workspace, so it falls back to the service default and cannot present workspace lifecycle state.

This change builds a UI integration layer over that existing boundary. It does not change workspace ownership, FIFO writes, storage authority, or status semantics.

## Page and State Model

```text
GET /webui/index.html                 GET /webui/manage.html
          |                                      |
          +----------- shared client state -------+
                              |
                    currentWorkspaceId
                              |
       +----------------------+----------------------+
       |                                             |
use actions                                  management actions
search / ask / sessions                      status / register / remove / repair / upload / sync
       |                                             |
workspaceId explicit                          existing lifecycle HTTP routes
```

The use page owns interaction state (sessions, prompt, selected workspace). The management page owns workspace inventory refresh, lifecycle-operation feedback, and workspace content maintenance (uploads and directory sync). Shared state stores only the selected logical workspace ID and validates it against the latest server status before use.

Session state is workspace-scoped in the browser: switching workspace must not send a session ID created under a different workspace. A first-release implementation may clear the active session selection on switch rather than migrate or copy sessions.

## Search Fallback Contract

1. Refresh or use cached `GET /api/v1/status` inventory.
2. Search the selected workspace with its explicit `workspaceId`.
3. If `hits` is non-empty, render those hits and stop.
4. If `hits` is empty, search each other workspace whose state is `ready`, in the deterministic inventory order.
5. Stop at the first non-empty response, render it with the actual source workspace, and present that fallback occurred.
6. If every candidate is empty, show an explicit no-results state with the searched workspace scope.

The algorithm does not retry unavailable/degraded workspaces and does not combine hit lists. Transport or contract failures are shown as errors rather than treated as zero hits, so a server fault cannot silently broaden search scope.

## HTTP Contracts

The WebUI consumes the existing endpoints:

| Purpose | Endpoint | Required UI behavior |
|---|---|---|
| Workspace inventory/status | `GET /api/v1/status` | Read overall state and workspace ID, name, path, lifecycle, reason, queue/freshness fields. |
| Register workspace | `POST /api/v1/workspaces` | Submit logical ID and path; refresh status on success. |
| Unregister workspace | `DELETE /api/v1/workspaces/{id}` | Confirm in UI; do not offer removal for the protected default workspace when server rejects it. |
| Repair workspace | `POST /api/v1/workspaces/{id}/repair` | Surface synchronous outcome then refresh status. |
| Search | `GET /api/v1/search` | Always include `workspaceId`, query, and limit. |
| Ask | `POST /api/v1/ask` | Always include current `workspaceId`, question, topK, and a session ID belonging to that workspace. |
| Upload/sync | Existing ingest routes | Management page captures the selected `workspaceId` when the operation starts and preserves their request encoding. |

The server continues accepting calls without `workspaceId` for compatibility. The UI is not permitted to omit it. If source-workspace metadata is absent from a search response, the UI derives the source from the request it made; no incompatible response rewrite is required.

## Bundled Resource Publication

Production WebUI assets are Maven resources and are published below `/webui`. The root route keeps redirecting to `/webui/index.html`. The resource handler must serve the same content types and prevent path traversal for bundled assets as it does for filesystem assets.

An explicitly configured `webui-dir` has priority for local development and controlled deployment overrides. Without an override, the server serves the bundled resources. Missing or invalid override configuration must not prevent the bundled UI from being available unless the user explicitly requests strict override behavior.

## Compatibility and Safety

- Logical workspace IDs, not filesystem paths, remain browser and API identities.
- All workspace writes use existing service endpoints and retain their synchronous outcome mappings.
- The management page exposes only the existing loopback administration surface; this change does not make it remotely accessible.
- A workspace switch must not silently re-target an in-flight request. Requests capture the selected ID at submission time and render results with that captured ID.
- The management page displays paths only as server-supplied status metadata; it does not infer identity from them.

## CLI LLM Safety Boundary

Codex and Copilot, when introduced as LLM providers, are text-generation adapters rather than knowledge-base agents. The service remains the sole owner of retrieval, session state, workspace files, SQLite, Lucene, and all mutations.

- The adapter supplies a composed text prompt and consumes only final text output.
- It MUST NOT grant the CLI provider access to the selected workspace, Git state, shell tools, or file-mutating capabilities.
- Provider command templates and any tool-disabling options are application-owned; user configuration may select a supported model and timeout but MUST NOT inject arbitrary executable arguments.
- Non-zero exit, timeout, malformed output, or empty output are provider failures and participate in the configured failover policy.
- Retrieved content is untrusted evidence in the composed prompt and cannot override the service's answer instructions.

## Test Seams

`KnowledgeBaseHttpApp` is the highest preferred seam. Its tests can assert root redirection, bundled or override static assets, and all HTTP contracts with a real application instance. The client fallback planner is a small pure seam only if extracting it avoids browser-only integration tests.

## Open Questions

%% Confirm whether status ordering is a stable user-visible ordering. If not, define the fallback order as selected workspace followed by workspace ID ascending.

%% Confirm the desired session behavior when switching workspace. This design chooses isolation and session reset as the safe default.

%% Ask fallback is intentionally excluded until “no usable source” can be represented without guessing from generated text.
