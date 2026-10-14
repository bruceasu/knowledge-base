## Why

全量测试显示部分测试仍假设旧的 CLI 直连模式、旧配置文件位置或平台相关的命令行转义，同时根路径重定向未满足已声明的临时重定向契约。这些偏差会掩盖真实回归并降低发布信心。

## What Changes

- 让 CLI 内容命令测试通过临时 HTTP 服务验证现行服务优先架构。
- 兼容历史配置文件位置，并固定配置加载优先级。
- 修复根路径到 WebUI 的临时重定向状态码。
- 让 CLI 文本生成器测试在 Windows 与非 Windows 上避免 shell 引号差异。

## Capabilities

### New Capabilities

- `runtime-contract-repair`: 保证 CLI、配置、根路径 WebUI 路由和 CLI 文本生成器的可测试运行时契约一致。

### Modified Capabilities

- 无。

## Impact

- `KnowledgeBaseCli` 相关测试、配置发现、HTTP 根路由和 `CliLlmClient` 测试。
- 不增加依赖、不改变工作区 HTTP API 或 WebUI 业务行为。
