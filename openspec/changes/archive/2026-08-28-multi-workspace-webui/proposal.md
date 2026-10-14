## Problem Statement

当前 WebUI 通过服务端默认 workspace 调用搜索、问答、上传和同步接口，页面没有明确的当前 workspace 概念。用户无法选择工作区、查看每个工作区的运行状态，上传目标也不透明。搜索在当前工作区没有命中时不会帮助用户发现其它已注册工作区中的内容。

同时，HTTP 服务当前从运行目录中的外部 `webui` 文件夹发布静态资源；打包后的服务不能保证自动带上同一套 WebUI 页面。

## Solution

提供一个面向多工作区的 Web 控制台：使用页默认在用户选择的当前 workspace 中搜索、问答和上传；搜索零命中时，按确定顺序自动尝试其它可用 workspace，并清楚标示结果来源。管理页显示各 workspace 的状态并提供已存在生命周期接口的可视化入口。

HTTP 服务将内置 WebUI 静态资源随应用包发布，并继续支持显式的开发期静态目录覆盖。现有 API 的默认 workspace 兼容行为保持不变；WebUI 始终显式传递 workspace ID。

## User Stories

1. As a knowledge-base user, I want to see and select my current workspace, so that every action has an unambiguous data scope.
2. As a knowledge-base user, I want search to start in my current workspace, so that results are relevant to my immediate work.
3. As a knowledge-base user, I want a zero-result search to try other available workspaces automatically, so that I can discover information without manually switching contexts.
4. As a knowledge-base user, I want every fallback result to identify its source workspace, so that I can judge its relevance.
5. As a knowledge-base user, I want uploads and directory syncs to target the current workspace, so that imported content is stored where I expect.
6. As an operator, I want a dedicated management page listing all workspaces and their health, so that I can distinguish ready, missing, degraded, and failed workspaces.
7. As an operator, I want to register, remove, and repair workspaces from the management page, so that I do not need a separate client for routine lifecycle operations.
8. As a knowledge-base user, I want use and management on separate pages, so that search/chat flow is not obscured by operational controls.
9. As a deployment user, I want the HTTP server to publish the bundled WebUI automatically, so that the packaged application is usable without copying frontend files.
10. As an existing API client, I want requests that omit `workspaceId` to retain their current default-workspace behavior, so that this UI improvement does not break integrations.

## Implementation Decisions

- The WebUI will have separate use and management page entry points with shared client-side workspace state and API utilities.
- The selected workspace ID is browser state. It is included in every workspace-scoped use-page request instead of relying on server-side default selection.
- Search fallback is sequential: query the selected workspace first; only if it returns zero hits, query other `ready` workspaces in the server status order until one returns hits. The response view identifies the selected and actual searched workspace.
- Workspace inventory and state will use the existing `GET /api/v1/status` contract. Workspace registration, removal, and repair will use the existing workspace lifecycle routes.
- The service will publish a bundled, versioned WebUI resource tree under the existing `/webui` URL space. An explicit `--webui-dir` remains a development or deployment override.
- Public API response shapes remain backward compatible. Additive metadata is permitted only when required to make fallback source and selected workspace observable.

## Testing Decisions

- The primary seam is the HTTP application boundary, because it observes static-resource publication, workspace routing, lifecycle endpoints, and response contracts without testing implementation details.
- HTTP application tests will cover the bundled use and management pages, root redirect, development-directory override, status display data, workspace-specific request forwarding, and the existing lifecycle response mappings.
- Browser-client unit tests, if introduced without a browser test framework, will test pure fallback planning and response rendering helpers; they will not duplicate server behavior.
- Existing `KnowledgeBaseHttpAppTest` is the prior art for server and static WebUI behavior. Existing workspace HTTP tests are the prior art for multi-workspace response contracts.

## Out of Scope

- Authentication, authorization, audit logging, and remote/non-loopback administration.
- A public asynchronous operation API or live progress streaming.
- Parallel aggregation, ranking, or merging of search hits across multiple workspaces.
- Persistent server-side per-user workspace preferences.
- Redesigning workspace queueing, storage, checkpoints, or lifecycle semantics defined by `refactor-to-webservice-base`.

## Further Notes

%% Whether Ask should fall back to another workspace when the current workspace has no usable sources remains unresolved. This change specifies automatic fallback only for Search.

%% The status endpoint's ordering is used for first-release fallback only if it is confirmed stable; otherwise the UI must use an explicit deterministic ordering rule without changing search semantics.
