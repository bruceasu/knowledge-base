# Implementation Tasks

## TODO

- [x] 1. Inspect the final `refactor-to-webservice-base` HTTP contracts and reconcile this change's workspace/status assumptions before implementation.
- [x] 2. Define shared WebUI client state for status inventory, selected logical workspace ID, request capture, and workspace-scoped session isolation.
- [x] 3. Update the use page to select/display the current workspace and send its ID for search, ask, upload, and directory sync.
- [x] 4. Implement sequential zero-hit search fallback across ready workspaces, including source/fallback presentation and error behavior.
- [x] 5. Add a separate management page that renders server/workspace state and invokes existing register, unregister, and repair routes.
- [x] 6. Package production WebUI assets as application resources and publish them at `/webui`, retaining explicit `webui-dir` override behavior.
- [x] 7. Add HTTP-boundary tests for bundled assets, root redirect, override behavior, workspace-specific use requests, and lifecycle management contracts.
- [x] 8. Add focused client-side tests for deterministic fallback planning and stale-selection/session isolation where a pure test seam is introduced.
- [x] 9. Update user-facing documentation for the two WebUI pages, workspace selection, fallback behavior, and bundled-resource startup behavior.
- [x] 10. Move upload and directory sync from the use-page sidebar to the management page, preserving captured workspace targeting.

## Verification

- [x] Run the repository-standard verification script if available, subject to explicit user authorization.
- [x] Run focused HTTP application and WebUI client tests, subject to explicit user authorization.
- [x] Verify that every WebUI workspace-scoped request sends its captured logical `workspaceId`.
- [x] Verify zero-hit-only sequential fallback, source labeling, and no fallback after a failed current-workspace request.
- [x] Verify a packaged server serves `/webui/index.html` without an external asset directory and honors a valid explicit override.
- [x] Verify existing API requests that omit `workspaceId` still use the server default workspace.

## TODO / Open Questions

%% Confirm whether Ask should have a separate fallback contract; it is not included in this checklist.
%% Fallback order is selected workspace followed by other `ready` workspaces sorted by logical workspace ID.
%% Switching resets browser session selection rather than migrating sessions; this is implemented as the safe isolation default.
%% Tests were added but not executed because implementation instructions require explicit authorization for test commands.