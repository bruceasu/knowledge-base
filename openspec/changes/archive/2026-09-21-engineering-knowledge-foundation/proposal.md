## Why

现有知识库已经具备文章、版本、分块、SQLite 持久化与 Lucene 检索能力，但这些概念尚不能以可追溯的工程知识模型连接需求、设计、代码与测试。先建立经真实仓库盘点验证的最小核心，能够为后续的结构化摄取、代码知识和 AI 上下文构建提供安全的迁移起点。

## What Changes

- 盘点当前知识库的模块、模型、存储、索引、摄取、公开契约及测试，并记录实际依赖与迁移分类。
- 新增独立于 SQLite、Lucene、HTTP、MCP 与外部命令的最小工程知识核心值对象。
- 定义 canonical/derived 知识、来源、证据与少量可追溯关系的基础术语和约束。
- 保持现有 `KnowledgeBase`、`KnowledgeRepository`、`LuceneSearchIndex`、HTTP 与 CLI 行为不变；Lucene BM25 继续作为可重建的词法检索投影。

## Capabilities

### New Capabilities

- `engineering-knowledge-foundation`: 基于真实仓库证据建立最小工程知识核心及其迁移盘点。

### Modified Capabilities

- 无。

## Impact

本变更新增规划交付物和纯 Java 核心值对象及其测试。它不改变 SQLite schema、文章/版本/chunk 持久化格式、Lucene 索引格式、现有 HTTP/CLI 契约、依赖版本或部署方式。
