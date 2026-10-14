## Why

文档知识 Adapter 可以在内存中产生带来源的对象，但跨文档关系、证据、派生投影过期状态仍没有独立、可恢复的权威存储。新增一个与旧文章业务表隔离的 SQLite 知识存储，可让后续代码知识、检索和 Context Builder 以可追溯数据为基础演进，而无需迁移或重写现有文章/版本/chunk 功能。

## What Changes

- 新增仅承载工程知识视图的 SQLite 表、迁移和具体存储组件：知识对象、关系、证据与索引状态分开保存。
- 建立可验证的幂等写入、读取、引用完整性、内容 hash、派生对象过期和投影重建状态语义。
- 保持旧文章、版本、chunk 及其现有 SQLite 表为原业务功能的权威来源；不进行旧数据回填或替换。
- 保持 Lucene BM25 为可重建的词法投影，不在本变更中接入 FTS5、替换 Lucene、修改 HTTP/CLI 或暴露新的服务接口。

## Capabilities

### New Capabilities

- `knowledge-provenance-store`: 在 SQLite 中持久化工程知识对象、关系、证据和可重建投影状态。

### Modified Capabilities

- 无。

## Impact

本变更会新增 SQLite schema 迁移、具体存储实现和持久化行为测试，因此具有数据库 schema 与事务风险。它依赖完成的 `engineering-knowledge-foundation` 与 `engineering-knowledge-document-adapter` 作为对象、身份、来源和派生数据语义的输入；不改变既有 API、旧表语义、Lucene 索引格式、依赖版本或部署拓扑。
