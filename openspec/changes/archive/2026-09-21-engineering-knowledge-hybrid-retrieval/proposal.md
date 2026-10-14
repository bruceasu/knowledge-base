## Why

工程知识已拥有多种互补来源：SQLite 中的当前结构化投影、Lucene BM25 词法召回、`rg` 精确文本、Ctags 符号和 Git 证据；让调用方分别理解这些工具会导致重复排序、去重和过期判断。新增一个统一、可解释的检索 Module，可将这些来源融合成确定性的候选结果，同时保留 Lucene BM25 的迁移友好价值。

## What Changes

- 新增深 Module `KnowledgeRetriever`，以单一 `retrieve(KnowledgeQuery) -> RetrievalResult` Interface 屏蔽 SQLite、Lucene、`rg`、Ctags 和 Git 的内部细节。
- 新增 `LuceneBm25LexicalRetriever` Adapter，复用现有 `LuceneSearchIndex` 作为可重建的词法召回/初始排序投影，而不是新的权威数据源。
- 融合 Store 结构化候选、BM25、精确文本、符号和 Git 证据，实施当前 canonical 优先、来源/版本一致性检查、确定性去重/排序、来源解释和部分来源失败诊断。
- 不替换现有 HTTP/CLI 搜索、不删除 Lucene、不引入 FTS5、向量检索、语义模型或公开 MCP；不改变 SQLite schema 或自动重新索引流程。

## Capabilities

### New Capabilities

- `hybrid-knowledge-retrieval`: 以单一可解释检索 Interface 融合结构化、BM25、文本、符号和 Git 证据候选。

### Modified Capabilities

- 无。

## Impact

本变更新增检索编排 Module、Lucene BM25 Adapter 与跨来源行为测试。它依赖已完成的知识投影摄取、SQLite Store、`rg`、Ctags 和 Git evidence Adapter；现有 `LuceneSearchIndex` 继续存在且不改变当前调用方。不会改动 schema、依赖版本、部署方式、HTTP/CLI 契约或现有搜索结果类型。
