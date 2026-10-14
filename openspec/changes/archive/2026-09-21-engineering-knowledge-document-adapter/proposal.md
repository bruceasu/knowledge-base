## Why

现有知识库以 `Article`、`ArticleRevision` 和固定大小的 chunk 支持编辑、同步与检索，但它们还不能以保留 Markdown/OpenSpec 语义结构的工程知识对象形式供后续关系、证据和上下文能力使用。先以只读 Adapter 建立可追溯的文档投影，可在不扰动现有摄取、搜索和持久化行为的前提下验证目标模型是否适配真实文档。

## What Changes

- 新增一个面向现有文章、版本和文档内容的只读文档知识 Adapter，其唯一公开 seam 将旧模型映射为结构化知识投影。
- 将一份文档及其 Markdown 标题层级映射为可定位的文档/章节知识对象；固定 token chunk 仅作为带来源与输入 hash 的派生投影保留。
- 为稳定对象标识、内容 hash、标题层级、来源位置、重复标题和缺少标题等情况规定可测试的映射规则。
- 保持现有 `Article`、`ArticleRevision`、`Chunk`、同步、编辑、SQLite、Lucene BM25、HTTP 和 CLI 行为不变；本变更不持久化新对象，也不替换既有摄取路径。

## Capabilities

### New Capabilities

- `document-knowledge-adapter`: 将既有文档及其版本转换为有来源、保留结构的工程知识投影。

### Modified Capabilities

- 无。

## Impact

本变更预计新增纯 Java Adapter、映射结果值类型和小型文档夹具测试。它依赖 `engineering-knowledge-foundation` 产出的经盘点确认的核心术语与身份映射；不改变任何现有 API、数据库 schema、索引格式、依赖版本、部署方式或已有 OpenSpec capability 的需求。
