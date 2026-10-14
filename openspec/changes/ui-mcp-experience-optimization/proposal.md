## Why

当前 WebUI 已覆盖搜索、问答、Session、文档编辑和运维入口，但运行状态、空结果、错误恢复、来源新鲜度与虚拟项目能力没有形成一致的用户路径。MCP 已具备六个稳定的只读工具和严格协议边界，但工具描述、调用引导、错误后的行动建议和结果消费体验仍偏基础；现在统一优化可以减少用户和 AI agent 的试错成本，同时保持现有服务边界。

## What Changes

- 统一 WebUI 的加载、空结果、成功、部分可用、错误和重试反馈。
- 改进搜索、问答和知识对象结果的来源、证据、路径、行号、新鲜度和截断信息展示。
- 在管理页补充虚拟项目、同步状态和受约束文件操作的可发现入口，并复用既有 `/api/v2` 路由。
- 改善 WebUI 的键盘可操作性、窄屏布局、通知和危险操作确认。
- 为六个 MCP 工具补充面向 agent 的用途描述、参数语义、调用示例和结果解释。
- 统一 MCP 的可行动诊断信息，明确参数错误、未找到、暂不可用、部分结果和超时后的处理建议。
- 保持现有 MCP 工具名、只读性质、stdio-only transport、`schemaVersion=1.0` 和现有 API 路由兼容。
- **BREAKING**：无计划中的破坏性协议或 HTTP API 变更；若后续需要新增必填字段或改变语义，另行建立版本化变更。

## Capabilities

### New Capabilities

<!-- No new standalone capability; this change deepens the two existing surfaces below. -->

### Modified Capabilities

- `webui-editorial-redesign`: 增加一致的运行反馈、结果证据展示、虚拟项目操作入口和可访问性体验要求。
- `engineering-knowledge-mcp-exposure`: 增加 agent-facing 工具说明、示例和可行动诊断要求，同时保持现有稳定协议边界。

## Impact

- WebUI 静态资源：`webui/index.html`、`webui/manage.html`、`webui/app.js`、`webui/manage.js`、`webui/styles.css`，以及对应的 `src/main/resources/webui/` 镜像资源。
- MCP schema/handler：`KnowledgeAgentToolSchemas`、`KnowledgeMcpToolHandlers` 及其测试；优先使用描述和诊断增强，避免改变已有调用字段。
- 可能更新 HTTP/WebUI contract 测试、MCP schema/handler 测试和用户使用文档。
- 不新增依赖，不改变数据库 schema、部署拓扑、认证边界或 MCP 网络传输方式。
