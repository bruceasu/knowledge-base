# Engineering Knowledge Foundation：架构盘点

> 盘点日期：2026-09-19  
> 范围：`engineering-knowledge-foundation` 的阶段 0；全部结论均来自本仓库静态源码、测试与配置文件阅读，未执行运行时验证。

## 结论摘要

- `KnowledgeBase` 是当前应用编排入口；它直接协调文件摄取、SQLite、Lucene、LLM 元数据、会话与问答。
- `data/kb.sqlite` 中的业务状态是当前权威数据；`index/lucene` 是由 SQLite document/chunk/metadata 重建的 Lucene BM25 词法检索投影。
- 现有 Article、版本、chunk 与搜索命中均应通过后续 Adapter 映射到新核心；它们不能直接实现或替代 `core.knowledge` 类型。
- 当前不存在 MCP transport 或 JSON-RPC/MCP runtime。外部 EventBus 的交付、认证、排序与生产者契约没有本仓库证据，必须保留为 `UNKNOWN / NEEDS VERIFICATION`。

## 已验证的运行时结构

```text
HTTP / CLI / 可选 EventBus
  -> KnowledgeBaseHttpService + WorkspaceContext
  -> KnowledgeBase
     -> KnowledgeRepository -> JDBC / SQLite（权威业务状态）
     -> LuceneSearchIndex -> FSDirectory Lucene（可重建 BM25 投影）
     -> TextChunker / metadata / LLM / embedding / session services
```

`KnowledgeBase` 在构造时创建 `data/kb.sqlite` 和 `index/lucene`（`src/main/java/me/asu/kb/service/KnowledgeBase.java:65-76`）。`rebuild()` 清空 Lucene 后从 repository 的 document、chunk 与 metadata 重新写入索引（同文件 `656-687`）；`LuceneSearchIndex.addChunk(...)` 直接使用 `KnowledgeRepository.DocumentRow`、`ChunkRow` 与 `EffectiveDocumentMetadata`（`LuceneSearchIndex.java:65-85`）。因此 Lucene 不是权威存储，也不是新的核心依赖边界。

## 模块、公开入口与依赖

| 范围 | 真实符号 / 证据 | 当前职责 | 依赖方向 |
|---|---|---|---|
| CLI | `cli/KnowledgeBaseCli.run` | `sync`、`add`、`search`、`ask`、`rebuild` 经 `KnowledgeBaseHttpClient` 使用 HTTP；`serve/http` 启动服务 | CLI -> HTTP client / app |
| HTTP | `http/KnowledgeBaseHttpApp.registerRoutes`、`KnowledgeBaseHttpService` | 对外暴露 health/status、workspace、ingest、sync/rebuild、search、ask、documents/revisions/trash、sessions 等 `/api/v1/*` 路由 | HTTP -> workspace/application service |
| 多工作区与并发 | `WorkspaceRegistry`、`WorkspaceContext` | 懒加载每个工作区的 `KnowledgeBase`，持有 FIFO 写队列、读写锁、超时和 SQLite busy/locked 重试 | HTTP -> workspace -> application |
| 应用编排 | `service/KnowledgeBase` | 协调摄取、版本、投影恢复、词法检索和问答 | application -> repository/index/ingest/LLM |
| 持久化 | `service/KnowledgeRepository` | JDBC SQLite、schema/migration、document/article/chunk/metadata/session 和投影批次 | repository -> SQLite JDBC |
| 摄取 | `ingest/TextChunker`、`OrgMetadataExtractor`、`HtmlMetadataExtractor`、`metadata/LlmMetadataService` | 解析格式、按章节分块、生成/提取元数据 | application -> ingest / metadata |
| 索引 | `service/LuceneSearchIndex` | `FSDirectory`、`StandardAnalyzer`、加权多字段搜索，输出 `SearchHit` | index -> Lucene + repository 行类型 |
| 可选事件 | `eventbus/KnowledgeBaseEventBusBridge`、`KnowledgeBaseHttpApp.startEventBusBridge` | 轮询 `/api/bus`，处理文件摄取与目录同步事件，调用 HTTP service | event bridge -> HTTP service |
| 配置 | `cli/KnowledgeBaseConfig.loadConfig`、`applyEnvironmentVariables`、`applyArgs` | TOML -> 环境变量 -> CLI 的覆盖顺序；配置 workspace、端口、LLM、EventBus 等 | entry point -> configuration |

没有发现 `me.asu.kb.core` 包，也没有发现 `KnowledgeObject`、`ObjectType`、`KnowledgeClass`、`SourceRef`、`Relation`、`RelationType`、`Evidence` 或 `EvidenceType` 的同名现有类型。唯一近似名称是面向问答返回的 `me.asu.kb.ask.SourceReference`（`ask/SourceReference.java:5-16`）；新增 `SourceRef` 不与它混用。

## 存储、迁移与投影事实

- `KnowledgeRepository.initSchema()` 建立 `workspace_checkpoints`、`projection_batches`、`projection_batch_paths`、`workspace_roots`、`documents`、`chunks`、metadata/tag/keyword/upload/embedding/session 等表（`KnowledgeRepository.java:1145-1305`）。
- 运行时迁移通过 `schema_migrations` 标记 `document-metadata-v1` 和 `article-revisions-v1`（`KnowledgeRepository.java:1559-1710`）。本变更不修改它们。
- `articles.article_id` 当前等于 `documents.id`；`ArticleRevision` 记录 hash、格式、source path、source kind 与创建时间（`KnowledgeRepository.java:151-170`、`ArticleRevision.java:7-31`）。
- `upsertDocument(...)` 在更新 document 时先删除旧 chunks（`KnowledgeRepository.java:42-60`）；因此 `ChunkRow.id` 不是长期稳定身份。
- `documents.id` 是 SQLite 自增键。永久删除后再次摄取会生成新 id，因此 `ArticleId` 可在当前工作区、修订和软删除/恢复期间稳定，但不能在 foundation 中承诺为跨重导入、跨 workspace 或跨迁移的 canonical ID。

## 现有概念到目标词汇的映射

| 现有概念 | 当前事实 | 目标映射 | 不能在本变更中假设的内容 |
|---|---|---|---|
| `Article` / `ArticleId` | 逻辑文章聚合，持有当前 revision、metadata、状态和投影状态（`Article.java:6-21`） | 未来 canonical `KnowledgeObject` 的输入候选 | `ArticleId` 不是全局 canonical ID |
| `ArticleRevision` | revision number、hash、format、source path、source kind、time | 未来 canonical 内容版本和 `SourceRef` 的部分输入 | 没有跨存储稳定 ID，也不含完整 derived provenance |
| `ArticleMetadata` / `EffectiveDocumentMetadata` | title、summary、keywords、tags；有效值有 manual/source/ai/fallback 优先级 | `KnowledgeObject.metadata` 的输入候选 | 不能独自表达 canonical 来源、generator version 与 input hash |
| `Chunk` / `ChunkRow` | chunk number、标题、正文、行范围 | future derived `KnowledgeObject` 与 `Evidence.location` 的输入候选 | row id、chunk number 和内容切分策略不能充当永久 ID；缺 chunker version/input provenance |
| `SearchHit` | document/chunk id、path、snippet、Lucene score、tags（`SearchHit.java:5-14`） | 后续检索结果或检索证据输入 | BM25 `score` 不是 `Evidence.confidence`，不是知识对象 |
| `KnowledgeRepository` | 具体 JDBC SQLite repository | 后续 SQLite `KnowledgeStore` Adapter 候选 | 当前只有一个实现，不新增空 Interface |
| `LuceneSearchIndex` | package-private、依赖 repository 行类型的 BM25 投影 | 后续 lexical retrieval/projection Adapter | 不进入 core，不替换既有搜索路径 |
| `workspace_events` | 运维审计事件记录 | 可能的 future trace 输入 | 没有关系词汇、来源位置或 evidence 语义，不能直接成为 `Evidence` |

## 迁移分类与耦合处理

| 分类 | 实际对象 | 处理结论与证据 |
|---|---|---|
| `KEEP` | HTTP/CLI 路由、`WorkspaceRegistry`、`WorkspaceContext`、现有 SQLite 与 Lucene 行为、相邻测试 | 是既有运行或公开兼容边界；foundation 不修改、不接入。 |
| `ADAPT` | `KnowledgeBase`、`KnowledgeRepository`、`LuceneSearchIndex`、`Article*`、chunk、metadata、`SearchHit` | 后续通过 document/store/retrieval Adapter 映射；不得让旧实体直接实现新 core 类型。 |
| `MOVE-LATER` | `LlmClient`、`EmbeddingClient`、`SummaryService`、`workspace_events` | 已有派生/扩展相关能力，但本阶段不把它们并入关系或检索抽象；任何移动须由后续 change 单独决定。 |
| `REWRITE-LATER` | `env.example` 中的 `PORT` / `WORKSPACE` 说明 | 与 `KnowledgeBaseConfig.applyEnvironmentVariables` 的实际读取入口存在漂移；文档校正不属于本变更。 |
| `DELETE-LATER` | `src/main/java/resources/db/db.sql` | 静态搜索没有运行时引用，且不在 Maven 默认资源目录、内容与 `initSchema()` 演进不同；删除前必须确认外部脚本未人工使用它。 |
| `UNKNOWN / NEEDS VERIFICATION` | 外部 EventBus 服务、MCP、部署时配置使用情况 | 仓库中只有 EventBus consumer bridge；没有生产者/服务端契约或端到端测试。源码和 `pom.xml` 没有 MCP transport/runtime，不能据此断言部署环境不存在外部集成。 |

## 本变更的核心边界

新增的 `me.asu.kb.core.knowledge` 仅提供纯 Java 不可变值对象：`KnowledgeObject`、`ObjectType`、`KnowledgeClass`、`SourceRef`、`Relation`、`RelationType`、`Evidence` 与 `EvidenceType`。

- `KnowledgeObject` 要求 caller-supplied stable ID、对象类型、标题、可选正文、来源、不可变 metadata、类别和内容 hash。
- `CANONICAL` 对象禁止派生 provenance；`DERIVED` 对象强制记录 canonical source ID、generator identity、generator version 与 input content hash。
- `Relation` 强制持有 `Evidence`；证据强制记录 type、source、location 和 `0.0..1.0` 的 confidence。
- 初始关系词汇严格限制为 `REFINES`、`DESCRIBES`、`IMPLEMENTS`、`VERIFIED_BY`、`RELATED_TO`。
- 核心不导入 SQLite、Lucene、HTTP、MCP、Spring 或外部进程类型，也没有被现有生产调用链引用。

## 已解除与保留的设计问题

- 已解除：现有 EventBus bridge、HTTP/CLI 入口、配置覆盖顺序、可扩展 Java seam（LLM/embedding/summary）以及当前无 MCP 实现均已有静态证据；因此 foundation 不需要创建事件、插件或 MCP core Interface。
- 已解除：`ArticleId` / chunk id 不满足新核心的跨重导入稳定 ID 语义。foundation 保留通用 ID 不变量；下一项 `engineering-knowledge-document-adapter` 必须明确 document/revision/chunk 的命名空间和 ID 策略。
- 保留：外部 EventBus 的认证、投递保证、重试、排序、生产者事件格式和部署时配置使用情况仍为 `UNKNOWN / NEEDS VERIFICATION`。
- 前置协调：`knowledge-base-reliability-performance` 是否已归档或显式冻结没有本 change 的受管任务证据；在后续触及 SQLite、Lucene 或检索链路前仍须确认。

## 下一项工作

下一项可实施 change 为 `engineering-knowledge-document-adapter`：基于本盘点定义 Article、revision、metadata 和 chunk 的 Adapter 与稳定 ID 策略；它不得修改当前 SQLite schema、摄取、Lucene 或 HTTP/CLI 路径。
