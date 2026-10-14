## ADDED Requirements

### Requirement: WebUI exposes consistent operation state and recovery

使用页和管理页的异步操作 SHALL 明确呈现 idle、loading、success、partial/unavailable 或 error 状态。加载中的操作 SHALL 防止同一操作被重复提交；失败状态 SHALL 保留安全的错误信息并提供重试或明确的下一步；刷新或切换上下文后不得继续显示已失效的操作结果。

#### Scenario: User submits a search

- **WHEN** 用户提交检索、请求仍在执行
- **THEN** 输入操作显示进行中状态并避免重复提交；请求完成后显示结果、空结果或可重试错误

#### Scenario: Management operation is partially available

- **WHEN** 状态或同步返回部分结果、降级状态或不可用诊断
- **THEN** 管理页保留可用数据，明确显示状态和诊断，并提供刷新或重试路径

### Requirement: WebUI makes evidence and freshness readable

检索、问答和知识对象结果 SHALL 在不改变后端响应的前提下清晰展示来源、相对路径、行号（如有）、scope/virtual project、freshness、evidence 和截断/不完整提示。缺失字段 SHALL 显示为明确的不可用状态，而不是伪造成功或新鲜度。

#### Scenario: User reads a search hit

- **WHEN** 检索结果包含来源和证据
- **THEN** 用户能够从结果卡片直接识别标题、摘要、来源位置、freshness 和证据状态

#### Scenario: Search response is incomplete

- **WHEN** 响应标记为 incomplete、truncated 或包含诊断
- **THEN** 页面在结果区域显示不完整提示，并保留已有结果，不将其渲染为完整答案

### Requirement: WebUI exposes safe virtual-project operations

管理页 SHALL 提供虚拟项目列表/选择、资源引用查看、显式同步和同步状态查看入口，并复用现有 `/api/v2/virtual-projects` 路由。第一版 UI SHALL 不默认暴露删除源文件的操作；任何可能改变源文件或移除项目数据的危险操作 SHALL 显示范围和二次确认。

#### Scenario: User synchronizes a virtual project

- **WHEN** 用户选择项目并请求同步
- **THEN** 页面显示同步进行中状态，完成后刷新项目状态、freshness 和诊断

#### Scenario: User views an external resource boundary

- **WHEN** 项目资源位于路径策略不允许的范围
- **THEN** 页面显示受控的拒绝诊断，不展示不必要的本地绝对路径，并保留修正配置/路径的操作提示

### Requirement: WebUI preserves offline accessibility and bundled asset integrity

新增体验 SHALL 继续使用本地静态资源，不依赖 CDN、远程字体或新的前端运行时。所有可交互控件 SHALL 保留可见焦点、可访问名称和窄屏可操作布局；`webui/` 与 `src/main/resources/webui/` 的同名资源 SHALL 保持一致。

#### Scenario: User operates the UI with keyboard or narrow viewport

- **WHEN** 用户仅使用键盘或在窄屏设备打开使用页/管理页
- **THEN** 操作控件可聚焦、可识别、可触达，主要流程不依赖悬停或水平滚动

