## Context

当前产品是本地单工作区服务，WebUI 与 MCP 共享 `/api/v2` 和 agent facade 的结果模型。WebUI 已有使用页和管理页，但两个页面主要以“请求完成/失败”为反馈单位，搜索结果、来源证据、freshness、截断和虚拟项目状态没有形成连续的操作路径。MCP 已有固定的六工具、stdio-only、只读和严格 schema 合同；当前 server 为所有工具提供相同的通用描述，诊断虽已安全化，但对调用方的下一步指导有限。

本变更的约束是：不引入前端框架或外部资源，不新增后端路由，不改变数据库和部署边界，不把 MCP 改成网络服务，不把只读工具扩展成写工具。WebUI 源资源必须继续与 `src/main/resources/webui/` 保持一致。

## Goals / Non-Goals

**Goals:**

- 让用户能明确知道当前操作处于等待、成功、部分成功、失败还是可重试状态。
- 让搜索/问答结果中的来源、证据、freshness、scope 和截断信息容易阅读和继续操作。
- 让虚拟项目的创建、资源引用、同步和状态在管理页可发现，并复用已有 HTTP API。
- 让 MCP 客户端从工具描述即可理解用途、参数边界和典型调用方式。
- 让 MCP 的非 OK 结果能够指导调用方修正参数、重试或接受部分结果。
- 保持现有公共工具名、请求字段、响应主结构和只读约束兼容。

**Non-Goals:**

- 不新增 JavaScript 框架、CDN、远程字体或运行时依赖。
- 不新增 `/api/v2` 路由，不改变 HTTP 请求/响应语义。
- 不新增 MCP 工具，不重命名现有工具，不新增必填输入字段。
- 不把 MCP 变成可写、远程或多工作区接口。
- 不在本变更中解决模型质量、检索排序或数据库性能问题。

## Decisions

### 1. 用共享的前端状态模型驱动反馈

在现有 `app.js` 和 `manage.js` 内引入轻量状态表示，至少区分 `idle`、`loading`、`success`、`partial`、`error` 和 `offline/unavailable`。异步请求在开始时锁定对应操作控件，在 finally 路径恢复控件；错误保留安全的服务端 message/code，并提供重试入口。

备选方案：引入前端状态管理库。拒绝原因是 WebUI 是随 jar 打包的离线静态资源，当前规模不需要依赖和构建链扩张。

### 2. 保持 API 复用，优先增强展示层

搜索、问答、状态、文档和虚拟项目展示直接消费现有 `/api/v2` 字段。对缺失字段使用明确的“暂无/不可用”状态，不在浏览器端猜测 freshness 或成功语义。虚拟项目能力使用现有项目、资源、sync、status 和 files 路由，不创建 UI 专用后端接口。

备选方案：为 UI 增加聚合 endpoint。拒绝原因是会扩大 API 合同和测试范围，当前服务已提供足够的细粒度操作。

### 3. MCP 先增强元数据与诊断，不改变 schema 版本

为六个工具建立集中描述目录：用途、何时使用、关键输入、结果边界和典型工作流。`KnowledgeMcpServer` 注册工具时使用各自描述；输入/输出 schema 继续保持严格和 `1.0`。错误继续通过已有 `status` 与 `diagnostics` 表达，使用稳定诊断 code 和安全、可行动的 detail；不直接增加新的必填响应字段。

备选方案：新增 `retryable`、`nextAction` 等响应字段。暂不采用，因为这会改变严格输出 schema；若实际 agent 反馈证明现有诊断不足，再单独设计 additive schema 变更。

### 4. 用 contract tests 固定体验合同

WebUI 测试继续验证源资源和 bundled 资源一致、端点不回退到 `/api/v1`，并增加关键状态/虚拟项目入口存在性检查。MCP 测试验证每个工具拥有非通用描述、描述不泄露本地路径、原有字段和版本不变，并覆盖代表性的可行动诊断。

## Risks / Trade-offs

- [界面状态复杂度上升] → 只在请求边界维护状态，并复用现有 `requestJson`/feedback 入口，避免引入全局框架。
- [虚拟项目操作误触发源文件变更] → UI 明确区分资源引用、同步和文件写操作；危险操作保留二次确认，并显示“源文件位于原路径”的提示。
- [MCP 描述与实际字段漂移] → 描述目录与 schema 同处 `KnowledgeAgentToolSchemas`/MCP 注册层，并增加 schema/description contract test。
- [诊断文案暴露内部信息] → 继续经过现有 sanitizer，只允许安全 code 和有界 detail；不展示绝对路径、SQL、堆栈或命令行。
- [源 WebUI 与 jar 资源不同步] → 每次 UI 修改同时更新两份资源，并由现有 `SingleWorkspaceWebUiContractTest` 校验。

## Migration Plan

1. 先实现 WebUI 状态、结果阅读和虚拟项目管理入口，保持现有 API 调用方式。
2. 同步更新 `src/main/resources/webui/` 并运行窄范围 WebUI contract/JavaScript 静态检查。
3. 增加 MCP 工具描述目录和可行动诊断文案，保持 schema version `1.0`。
4. 更新 MCP schema/handler/server 测试和使用文档。
5. 如发现需要新增响应字段，停止在本变更中扩展，另建版本化 MCP 合同变更。

回滚只需恢复 WebUI 静态资源和 MCP 描述/诊断实现；不涉及数据迁移或 workspace 回滚。

## Open Questions

- 虚拟项目 UI 第一版是否包含文件写入操作，还是只展示项目/资源/同步/状态？默认先做只读管理和显式同步入口，文件写入保留 API 能力但不在第一版 UI 暴露。
- MCP 工具描述是否需要同时加入中文示例？默认描述使用英文以适配多数 MCP client，诊断 detail 保持简洁英文；可在实现时根据现有客户端展示能力补充多语言文案。
