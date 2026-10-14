## Why

工程知识平台需要能够精确定位代码标识符、配置键、错误文本和文件内容的证据，但不能让调用方直接拼接 shell 命令或把文本命中误判为实现关系。将 ripgrep 封装为受限、可诊断的代码搜索 Adapter，可先交付确定性的本地文本证据能力，并为后续混合检索提供一个稳定输入。

## What Changes

- 新增 `RipgrepCodeSearchAdapter`，通过一个受控请求/结果 seam 提供工作区内的精确文本和受限路径搜索结果。
- 将每个命中映射为带工作区相对路径、行列范围、受限摘录和核心来源/证据数据的候选；命中不自动产生 `IMPLEMENTS`、`CALLS` 或其他事实关系。
- 规定工作区边界、无 shell 参数传递、查询/输出/超时限制、确定性排序以及工具缺失、失败和截断时的诊断语义。
- 不改现有 `KnowledgeBase`/Lucene 搜索、SQLite schema、HTTP/CLI 或检索路由；不实现符号解析、Git 历史、正则自由搜索或持久化。

## Capabilities

### New Capabilities

- `ripgrep-code-search`: 在受限工作区内生成可追溯、可诊断的精确代码文本搜索候选。

### Modified Capabilities

- 无。

## Impact

本变更预计新增一个外部进程 Adapter、请求/结果值类型和隔离夹具测试。它依赖已完成的核心来源语义和 SQLite 知识存储边界，但本阶段只返回内存候选；不新增第三方依赖、不改变已有索引或公开契约，也不要求项目把 `rg` 作为服务端唯一真相。
