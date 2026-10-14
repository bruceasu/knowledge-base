## Context

`engineering-knowledge-foundation` 定义存储无关的对象、关系、证据和 canonical/derived 语义；`engineering-knowledge-document-adapter` 用这些语义产生只读文档投影。本变更首次让该投影可恢复，但不接管现有文章业务：既有 `KnowledgeRepository` 所管理的文章、版本、chunk 和相关表仍是原功能的权威来源。

当前部署目标仍是一个 Java 服务实例、同一主机本地磁盘上的 SQLite、短事务、单写入协调和多并发读取。SQLite 是当前权威存储，Lucene BM25 是由已提交数据可重建的词法投影。本 change 不能借由新增表改变这一服务化边界，也不能把未来 PostgreSQL、多节点或 FTS5 的选择提前实现。

## Goals / Non-Goals

**Goals:**

- 以增量、隔离的 SQLite schema 持久化 `KnowledgeObject`、关系、关系证据和投影/index 状态。
- 通过一个具体且窄的 SQLite 存储 seam 提供原子写入、按 ID 读取、幂等重放、引用完整性和派生数据过期标记。
- 使一个关系不能在缺少证据的情况下被成功写入，使 derived 对象能追溯 canonical 来源、生成器和输入 hash。
- 为新表的首次创建、既有数据库升级、写入失败、重开读取和重放建立可测试的恢复边界。

**Non-Goals:**

- 不迁移、改写、删除或重新解释既有 article/revision/chunk/metadata 表及其数据。
- 不修改既有 `KnowledgeRepository`、文章编辑/同步事务、`KnowledgeBase`、Lucene 索引格式、HTTP/CLI 契约或部署配置。
- 不创建只有单一实现的通用 `KnowledgeStore` Interface；不实现 PostgreSQL、双写、多节点复制、WAL 配置切换、FTS5 或 OpenSearch。
- 不在本 change 引入代码提取、检索编排、Context Builder、MCP 或基于 LLM 的关系确认。

## Design Readiness

**FD_APPLIED**

本 change 涉及持久化、迁移、恢复与并发边界，已完成受管 Feature Design 审视。术语保持为：`KnowledgeObject` 是可持久化的工程知识对象；canonical 是事实来源，derived 是可由来源和生成器重建的投影；`Relation` 必须携带至少一条 `Evidence`；`knowledge_index_state` 只记录可重建投影的状态。

**执行前置 Gate：**

1. `engineering-knowledge-foundation` 和 `engineering-knowledge-document-adapter` 的对象、稳定 ID、来源和 hash 测试必须完成；
2. foundation 盘点必须已确认当前 SQLite migration 机制、现有表所有权、事务边界和恢复行为；
3. 影响 SQLite/Lucene 链路的 `knowledge-base-reliability-performance` 变更必须已归档或明确冻结；
4. 实施前必须完成针对实际 schema 的 migration/rollback 审查并得到明确实现授权。

最高测试 seam 是具体存储组件的原子“写入一份知识投影 / 按 ID 读取 / 查询投影状态”行为，使用临时 SQLite 数据库验证；测试不经过 HTTP、CLI、Lucene 或现有文章服务。

## Decisions

### 1. 采用四张新增、隔离的表，而非改造旧业务表

本 change 只新增以下逻辑表，名称与最终列类型由已确认的当前 migration 机制落地：

- `knowledge_object`：对象 ID、对象/知识类别、canonical-or-derived 标记、标题/内容或受控内容引用、来源数据、内容 hash、元数据、来源对象、生成器和输入 hash；
- `knowledge_relation`：关系 ID、起点对象 ID、关系类型、终点对象 ID，以及支持审计和去重的受控标识；
- `knowledge_evidence`：证据 ID、所属关系、证据类型、来源与位置、置信度及必要的可审计载荷；
- `knowledge_index_state`：投影名称/版本、对象或输入 hash、状态、最后成功时间和可恢复错误信息。

旧表不加外键、不改列、不回填数据。新知识表引用核心对象 ID，而不是让旧 article row 成为其隐式所有者。这样可以在失败时停止新写入而保留旧功能，也不会把工程知识 schema 与文章编辑模型耦合。

### 2. 使用一个具体的 SQLite 存储组件，暂不泛化 Interface

实现一个具体 SQLite 存储组件，负责在一个调用中校验并保存完整知识投影，或按明确的 ID/状态读取它。调用者不直接拼 SQL，也不直接依赖 SQLite 行模型。

不提前暴露通用 `KnowledgeStore` Interface：目前只有 SQLite 这个真实实现，空抽象只会增加转发层。出现 PostgreSQL 或第二个真实存储实现时，后续迁移 change 才在真实调用者边界提炼 Interface。

### 3. 写入以对象投影为原子单位并强制证据完整性

单次保存必须在一个短 SQLite 写事务中完成：验证对象 ID、derived 来源、关系两端和至少一条关系证据；写入/更新对象、证据、关系和对应 index state；全部成功后才提交。失败时事务必须整体回滚，不能留下无证据关系、悬空对象引用或“已完成”投影状态。

对相同稳定 ID、相同内容 hash、相同生成器版本和相同关系/证据集合的重放必须幂等，不能产生重复行。对象内容变化的精确更新/替换语义必须以 core 的 canonical/derived 不变量为准，而不是按 SQLite 自增 ID 或写入时间推断。

### 4. 用 hash 驱动 derived 过期，而非同步删除或重建

canonical 内容或其来源 hash 变化后，Store 必须把依赖该输入 hash 的 derived 对象和相关 `knowledge_index_state` 标为 stale/retryable。它不得在同一写事务里执行 Lucene 重建、外部命令或长时间解析；后续投影 worker/Adapter 负责根据已提交状态重试或重建。

因此，Lucene 只消费已提交且可追溯的投影输入。索引失败也不会损害 SQLite 中已经提交的 canonical 对象或关系。

### 5. 迁移只向前新增，回滚以停用新路径为主

迁移必须使用仓库当前已确认的 migration 机制，以版本化、可重复的方式创建新表和必要索引。迁移测试必须覆盖空数据库和含既有旧表的升级数据库，证明旧表与数据未被修改。

回滚时停止新 Store 的读写和后续投影，不回滚或删除已创建的新表；新表保留便于审计和恢复，旧文章功能继续依赖原有表。任何物理删除、旧数据迁移、双写或 schema 收缩都需要后续独立 change 与明确批准。

### 6. 保持单机 SQLite 事务与服务化边界

实现必须复用或尊重当前 SQLite 连接、事务和关闭约定，保持写事务短小。它不得假设网络文件系统上的多进程共享 SQLite，也不得在本 change 改变 WAL、锁定、连接池或部署配置。

当实际指标证明单实例单写模型成为瓶颈时，后续服务化迁移可以在这一具体组件的调用边界引入服务器端 Adapter；调用者不应因此依赖本 change 的行结构或 SQL。

### 7. 关系与证据以 Store 投影边界聚合并拥有确定性身份

核心 `Relation` 保持为带一条必填 `Evidence` 的存储无关值对象，不修改其 API。具体 Store 新增 `KnowledgeProjectionWrite` 和 `RelationProjection`：后者表示一个有向三元组 `(sourceObjectId, type, targetObjectId)` 及其 `List<Evidence>`，并提供从现有 `Relation` 创建单证据投影的便利入口。写入命令可以携带空 evidence 列表，仅用于让 Store 在短事务中拒绝非法调用；已提交的 relation 永远至少有一条 evidence。

同一端点顺序和 `RelationType` 的 relation 使用 `relation:v1:` 加上版本化、长度前缀 UTF-8 字段的 SHA-256 作为稳定 ID。evidence 使用 `evidence:v1:` 加上 relation ID、evidence type、source system/locator/revision（包含 revision 是否存在）、location 和 `Double.doubleToLongBits(confidence)` 的同类摘要作为稳定 ID。相同三元组的多条不同 evidence 聚合为一条 `knowledge_relation` 和多条 `knowledge_evidence`；完全相同的 evidence 去重。证据缺席不表示删除，删除语义留给后续有 projection owner 的 change。

`KnowledgeProjectionWrite` 用 `ObjectWrite` 显式携带 derived 对象实际消费的 `inputObjectId`。它与核心的 `canonicalSourceId` 分开：前者标识其 `inputContentHash` 对应的 canonical 输入，后者仍表示逻辑 canonical 来源。这样 document chunk 可以逻辑上源自 section、但消费 document 的原始字节 hash，而 Store 仍能精确验证和失效依赖。derived 对象和 index state 均保存该输入对象与 hash；canonical 从旧 hash 更新时，在同一事务将匹配旧输入的 derived 对象标为 `STALE`，并将对应非失败 index state 标为 `STALE`。不执行 Lucene 或外部工作。

### 8. 具体 Store 自持前向迁移与停用式恢复边界

当前唯一的 legacy migration runner 是 `KnowledgeRepository` 的私有 `initSchema()`，而本 change 不得修改该类。因此 `SqliteKnowledgeStore(Path)` 自持 JDBC connection、短事务和 migration runner；它在同一数据库中以现有 `schema_migrations` 表、唯一版本 `engineering-knowledge-v1`、`CREATE TABLE IF NOT EXISTS` 和成功后写入 marker 的方式创建新增表及必要索引。它不调用、复制到或改变 legacy `KnowledgeRepository` 的连接、表或迁移路径。

迁移前由调用方在停止使用目标 SQLite 文件后执行文件级备份；Store 不自动复制、恢复或删除数据库。迁移内的失败回滚事务且不写入 version marker；迁移后的回滚是停止调用新 Store 和后续投影，保留新增表供审计或受控恢复，绝不执行 down migration 或删除旧/新表。当前 change 不把 Store 接入 legacy FIFO writer 或生产路径，因此不形成同库并发写入；后续 Adapter 接入必须先定义协调边界。

## Risks / Trade-offs

- [新 schema 与现有表或 migration 机制冲突] → 以 foundation 的实际盘点为 Gate，在实现前先在复制数据库验证升级，并仅使用新增命名空间。
- [关系缺少证据或引用悬空] → 在同一事务中验证关系端点和至少一条证据，失败即回滚。
- [hash 变化导致投影状态不一致] → 写入和 stale 标记同事务提交；索引工作仅消费已提交状态。
- [SQLite 长事务阻塞读写] → 保存一份投影时只执行本地短 SQL，不运行重建、外部命令或网络 I/O。
- [回滚误删审计资料] → 回滚仅停用新路径，绝不自动删除新表或旧表。
- [过早抽象存储] → 只提供具体 SQLite seam，等真实第二实现需求出现后再提炼 Interface。
- [relation/evidence 的稳定标识碰撞或不一致] → 使用版本化、长度前缀字段的 SHA-256，不使用 SQLite 自增 ID、时间或 Java `hashCode`。
- [derived 的逻辑来源与实际 hash 输入不同] → 用 Store 专用 `inputObjectId` 显式保存 hash 输入，避免把 `canonicalSourceId` 错当作 hash 依赖。
- [独立迁移 runner 与 legacy 写入并发] → 当前不接入生产写入；后续接入前必须定义与 workspace FIFO writer 的协调，不能假定两条连接可安全并发写同一文件。
- [没有自动备份或 down migration] → 迁移前由调用方进行停机文件备份；失败后停用 Store 并保留表，恢复操作另行受控执行。

## Migration Plan

1. 验证全部前置 Gate，记录当前 schema/migration/transaction 的实际证据和本 change 的目标数据库版本。
2. 增加前向 migration 与最小索引，在全新和升级数据库中验证新表隔离性。
3. 实现具体存储组件及原子保存、读取、幂等、引用完整性、stale 状态和失败恢复测试。
4. 保持新存储未接入现有搜索和 HTTP/CLI 入口；文档/代码 Adapter 后续以受控调用接入。
5. 更新 TODO、Verification 和部署/恢复证据。若需要停止使用，关闭新写入和投影，让旧路径继续运行。

## Open Questions

无。实际列名和 SQL 方言细节必须遵守上述独立、前向迁移、确定性身份和停用式恢复边界；若实现证据要求改变这些语义，实施者必须先更新本设计和规范，不能在代码中隐式偏离。
