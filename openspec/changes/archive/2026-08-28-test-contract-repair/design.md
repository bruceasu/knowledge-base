## Context

内容命令已经通过 `KnowledgeBaseHttpClient` 调用服务，配置加载器有固定的默认路径顺序，WebUI 根路径应重定向到页面。现有测试仍保留部分旧假设，且 Windows shell 命令在参数转义方面与 Unix 不同。

## Goals / Non-Goals

**Goals:**

- 用真实临时服务验证 CLI 到 HTTP 的路径。
- 兼容已出现的历史配置文件位置，不改变更高优先级来源。
- 明确根路径使用 HTTP 302 临时重定向。
- 消除测试对 cmd 引号格式的依赖。

**Non-Goals:**

- 不恢复 CLI 直接打开 SQLite/Lucene 的旧模式。
- 不改变现有 HTTP API、配置字段或 LLM 文本输出语义。

## Decisions

- CLI 测试在端口 0 启动 `KnowledgeBaseHttpApp`，并将命令的 `--server-url` 与 `--workspace-id` 指向它；这验证当前生产边界而非伪造客户端。
- 在现有 `$HOME/.config/knowledge-base/knowlege-base.toml` 前加入旧的 `$HOME/.config/knowlege-base.toml` 作为更低优先级兼容来源，保留当前规范位置可覆盖旧文件。
- 根路由调用 `redirect(..., false)` 以符合 302 临时重定向定义。
- Windows 测试使用不需 shell 解释的 Java 可执行文件/命令，或只断言工具输出去除外层引号后的文本；生产 `CliLlmClient` 不为测试特例改变。

## Risks / Trade-offs

- [兼容配置产生意外覆盖] → 旧路径置于规范路径之前，CLI/环境变量仍优先。
- [CLI 测试启动服务变慢] → 使用临时目录、端口 0 和测试结束关闭服务。
- [重定向框架行为不同] → 以 HTTP 集成测试验证状态码和 Location。

## Migration Plan

修改后运行聚焦的 CLI、HTTP 和 LLM 测试，再运行全量 Maven 测试；回滚只需回退这些小型实现和测试改动。
