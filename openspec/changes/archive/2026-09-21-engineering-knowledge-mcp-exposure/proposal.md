## Why

在工程知识能力稳定于 Java 应用层后，Codex、Copilot 等代理需要稳定、受限且可解释的操作入口，而不是直接访问 SQLite、Lucene 或本地工具。以本地 stdio MCP 暴露只读领域操作，可将查询、上下文和追溯能力交给代理，同时保留工作区、预算、来源和错误边界。

## What Changes

- 新增本地 stdio MCP transport Adapter 和一个应用层工具 facade，稳定暴露 `knowledge_search`、`knowledge_get`、`code_symbol`、`code_search`、`trace` 与 `build_context` 六个只读工具。
- 为每个工具定义版本化输入/输出 schema、workspace/预算验证、结构化错误映射、超时/取消边界，以及不泄漏底层 SQL、Lucene、命令参数或绝对路径的结果规则。
- 所有工具只调用现有应用层 Modules（检索、Store read、代码搜索、受控 relation trace、Context Builder），并透传 provenance、freshness、incomplete 和诊断信息。
- 在 MCP facade/schema/transport 开始前，补齐当前盘点确认缺失的三个应用层前置边界：受控知识对象读取、固定范围且有证据的关系追踪，以及与 HTTP 生命周期解耦的不可变工程 workspace catalog；这些边界仍属于本 change 的同一交付生命周期，且不得暴露 SQLite、HTTP registry 或 Context Builder 内部类型。
- 初期仅支持启动本地进程的 stdio MCP；不增加网络监听、远程 MCP、HTTP 路由、认证/权限模型、写操作、依赖升级或现有 CLI/HTTP 行为修改。

## Capabilities

### New Capabilities

- `engineering-knowledge-mcp-exposure`: 以本地 stdio MCP 向代理暴露受控、只读、可追溯的工程知识操作。

### Modified Capabilities

- 无。

## Impact

本变更新增一个外部工具协议契约和本地 transport Adapter，因此包含 API/安全边界风险。它依赖 Context Builder、混合检索、Store/read 和代码 Adapter 的已验证应用层 Interface；不改 SQLite schema、Lucene、既有 HTTP/CLI、网络部署或旧业务数据。若仓库没有已批准的 Java MCP runtime，新增依赖必须另行获得明确批准，不能在实现时隐式引入。
