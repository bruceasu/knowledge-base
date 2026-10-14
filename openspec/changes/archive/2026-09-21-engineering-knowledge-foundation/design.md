## Context

当前实现以 `KnowledgeBase` 协调摄取、搜索和问答，以 `KnowledgeRepository` 持久化文档、文章版本、分块与投影状态，并由 `LuceneSearchIndex` 建立可重建的 BM25 词法检索投影。该变更是 `docs/handoffs/refactor.md` 和 `docs/handoffs/plan.md` 中长期工程知识平台路线的第一步，只覆盖阶段 0（盘点）和阶段 1（最小核心）。

## Goals / Non-Goals

**Goals:**

- 以实际类、表、公开入口和测试为依据形成架构清单、耦合分析、KEEP/ADAPT 映射及目标模型映射。
- 建立能表达工程知识对象、来源、知识类别、关系和证据的最小且不可变的核心值对象。
- 确保任何后续 Adapter 均可基于稳定术语接入，而不迫使现有实体直接实现新模型。
- 保留 SQLite 为当前权威存储，保留 Lucene BM25 为可重建词法检索投影。

**Non-Goals:**

- 不新增 SQLite 表、迁移或事务边界。
- 不修改 `KnowledgeBase`、`KnowledgeRepository`、`LuceneSearchIndex`、现有摄取流程、HTTP/CLI 或 MCP。
- 不实现 Markdown/OpenSpec Adapter、代码符号提取、关系持久化、混合检索、Context Builder、FTS5、Python 集成或服务端数据库迁移。
- 不添加或升级依赖，不创建 worktree，不运行测试、构建或性能测量。

## Design Readiness

**FD_APPLIED**

已完成受管 Feature Design 评估。术语以本变更中的最小定义为准；现有 `Article`、`ArticleRevision`、chunk 与搜索命中如何映射到目标对象，必须由任务 1 的静态盘点确认，不能预先假设。盘点 Gate 已由 [architecture-inventory.md](architecture-inventory.md) 解除：每个候选概念均已列出实际来源、耦合与 `KEEP`/`ADAPT` 等分类；核心仍不把旧实体直接接入新模型。

## Decisions

### 1. 以盘点作为首个实现步骤

先记录实际模块、包、公共入口、存储、索引、配置、事件和测试，再决定每个目标概念是否需要新类型或适配。盘点结论写入本 change 的交付文档；未能由代码确认的内容标记为 `UNKNOWN / NEEDS VERIFICATION`。

### 2. 最小核心只包含值对象与不变量

在候选 `me.asu.kb.core.knowledge` 包中定义 `KnowledgeObject`、`ObjectType`、`KnowledgeClass`、`SourceRef`、`Relation`、`RelationType`、`Evidence` 与 `EvidenceType`。核心不得导入 SQLite、Lucene、HTTP、MCP、Spring 或外部进程类型。

`KnowledgeObject` 必须区分 `CANONICAL` 与 `DERIVED`；派生对象必须保留其 canonical 来源、生成器信息和输入内容 hash。`Relation` 必须保留 `Evidence`，且证据包含来源、位置、类型与置信度。初始关系词汇仅限 `REFINES`、`DESCRIBES`、`IMPLEMENTS`、`VERIFIED_BY` 与 `RELATED_TO`。

### 3. 暂不创建空的检索或存储 Interface

此阶段没有第二个真实的存储或检索实现，因此不新增仅转发的 `KnowledgeStore` 或 `KnowledgeRetriever` Interface。现有最高测试 seam 为纯核心值对象的公开构造/查询行为；后续在 Lucene BM25 词法检索与结构化检索同时接入时，才创建检索 seam。

### 4. 保持当前索引与服务化演进方向

SQLite 继续保存权威业务状态；Lucene BM25 继续保存可由已提交 SQLite 状态重建的投影。首个服务化部署模型仍是单 Java 实例、同主机本地持久磁盘、短事务、单写队列与多读；这不是本变更的运行时实现工作。未来多节点或高并发写入需求通过 `KnowledgeStore` Adapter 迁移，而非让调用方依赖当前 SQLite 或 Lucene 类型。

## Risks / Trade-offs

- [盘点遗漏隐藏耦合] → 盘点必须引用实际类、测试与公开入口；无法确认的关系用 `UNKNOWN / NEEDS VERIFICATION` 标示。
- [核心模型与既有术语冲突] → 在新增类型前完成与 `Article`、`ArticleRevision`、chunk、`SearchHit` 的映射；不通过让旧实体实现新类型来掩盖冲突。
- [过早抽象] → 本阶段不引入仓储、检索、摄取或上下文构建 Interface。
- [误伤既有兼容性] → 禁止改动现有持久化、索引、HTTP/CLI 和依赖；新增核心不接入生产路径。

## Migration Plan

1. 静态盘点并形成可审阅映射，确认当前可靠性与性能变更已冻结或归档。
2. 根据盘点新增纯核心值对象和边界测试，不接入现有运行路径。
3. 更新本 change 的 TODO、Verification 与未决 `%%` 注记。
4. 后续 change 才能引入文档 Adapter、关系存储、代码知识与 Context Builder；每一步保持旧路径可回滚。

## Open Questions

%% 外部 EventBus 的认证、投递保证、排序、重试、生产者事件格式和部署时配置使用情况没有本仓库证据；后续需要事件或 MCP 接入时必须再次确认，不能从当前 consumer bridge 推断契约。

%% `ArticleId` 和 chunk row ID 不具备跨重导入的 canonical ID 语义。下一项 document Adapter 必须定义对象命名空间与稳定 ID 策略；本变更只约束核心 ID 为 caller-supplied stable identifier，不调整 SQLite schema。
