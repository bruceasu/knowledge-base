## Why

文档、符号和 Git Adapter 已能生成经过约束的候选，而 SQLite Store 能原子保存知识投影；两者之间若没有明确协调者，后续结构化检索将没有可靠、可重跑的数据来源。新增一个受控摄取 Module，可把经验证的 Adapter 输出幂等写入权威知识表，同时保持既有同步和搜索路径不变。

## What Changes

- 新增一个深 Module：`ingest(IngestionRequest) -> IngestionReport`，在内部协调文档、符号和 Git 证据 Adapter 与具体 SQLite Store。
- 规定输入快照/hash、对象/证据规范化、每个来源的原子持久化、幂等重跑、过期状态、部分来源失败和可审计摄取报告。
- 初期提供显式调用的应用层能力，不自动挂接既有文章同步、文件监控、HTTP、CLI 或定时任务；不会让现有路径产生双写。
- 不新增 schema、检索路由、Lucene 投影、LLM 推断、MCP 或外部服务部署；不改变旧文章/版本/chunk 的权威语义。

## Capabilities

### New Capabilities

- `knowledge-projection-ingestion`: 将受控知识 Adapter 输出以可追溯、幂等方式写入工程知识 SQLite 投影。

### Modified Capabilities

- 无。

## Impact

本变更会新增应用层摄取协调逻辑及针对真实 SQLite Store 的聚合测试，但不改变数据库 schema。它依赖 foundation、document Adapter、SQLite Store、Ctags 和 Git evidence Adapter；ripgrep 仍为查询期候选，不参与持久化摄取。既有 `KnowledgeBase` 同步、Lucene、HTTP/CLI、配置和部署保持不变。
