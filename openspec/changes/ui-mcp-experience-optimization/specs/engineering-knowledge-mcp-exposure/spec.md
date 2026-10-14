## ADDED Requirements

### Requirement: MCP tools advertise task-oriented descriptions

六个只读 MCP 工具 SHALL 提供各自的用途描述，至少说明适用问题、关键输入、结果边界和与其他工具的关系。描述 MUST 与严格输入/输出 schema 一致，不得承诺写入、网络访问、任意图遍历或未实现的过滤能力。

#### Scenario: Client lists the available tools

- **WHEN** MCP client 请求工具列表
- **THEN** `knowledge_search`、`knowledge_get`、`code_symbol`、`code_search`、`trace` 和 `build_context` 各自返回非通用且可区分的描述，同时保留只读 annotations 和 schema version `1.0`

#### Scenario: Agent chooses a tool from its description

- **WHEN** agent 需要搜索知识、查对象、找符号、搜代码、追踪关系或构建上下文
- **THEN** 工具描述能指导其选择对应工具，并提示必要参数和结果限制

### Requirement: MCP diagnostics guide safe recovery

每个受控的非 OK MCP 响应 SHALL 提供至少一个经过清理的稳定诊断 code 和有界 detail。诊断 SHALL 能区分参数修正、对象/项目不存在、服务暂不可用、部分结果、超时和内部失败等处理方向；不得暴露堆栈、绝对路径、SQL、命令行或秘密。

#### Scenario: Agent sends an invalid request

- **WHEN** 请求包含未知字段、错误枚举、超出预算或非法项目标识
- **THEN** MCP 返回 `INVALID_ARGUMENT` 或 `NOT_FOUND`，并给出可指导调用方修正请求的安全诊断

#### Scenario: Agent receives partial results

- **WHEN** 底层返回可用结果和源不可用/截断诊断
- **THEN** MCP 保留有界结果，返回 `INCOMPLETE` 或现有不完整状态，并说明应接受结果、刷新或重试

### Requirement: MCP experience improvements remain protocol-compatible

体验优化 SHALL 保持现有六个工具名、stdio-only transport、只读语义、请求字段、主响应结构和 schema version `1.0`。不得新增必填输入字段、删除已有字段、改变字段语义或把诊断改为未约束的自由文本协议。需要这些变化时 MUST 建立单独的版本化变更。

#### Scenario: Existing client invokes an unchanged tool

- **WHEN** 现有 client 使用当前 `1.0` 输入调用任一工具
- **THEN** server 继续接受请求并返回兼容的 `status`、`complete`、`data` 和 `diagnostics` 结构

#### Scenario: Tool metadata is enhanced

- **WHEN** server 发布更具体的工具描述或安全诊断文案
- **THEN** 不改变工具名、输入必填性、只读 annotation、响应上限、超时和错误安全边界
