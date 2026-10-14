## Context

当前目标部署是单个 Java 服务实例、同主机本地持久磁盘上的 SQLite、短事务、单写入协调和多并发读取；Lucene BM25 是可由已提交 SQLite 状态重建的词法投影。SQLite、`-wal` 和 `-shm` 不得被多个节点经 NFS/SMB/EFS 等网络文件系统共享。这个模型在没有实际分布式需求时比服务端数据库和独立搜索集群更简单、可恢复、成本更低。

本 change 不是“迁移到 PostgreSQL/OpenSearch”的预批准。它是所有前序能力具备兼容性、恢复、增量投影和可观测证据后，用来判断是否真的需要下一步的决策 Module。它只更新自己的 OpenSpec 决策记录，绝不在这个 change 实施 schema、部署或删除。

## Goals / Non-Goals

**Goals:**

- 固定一份可审计的触发条件和证据包，区分真实运行瓶颈与对未来规模的猜测。
- 评估继续本地架构、服务端存储 Adapter、独立 lexical retrieval Adapter 和遗留清理的独立风险/收益/回滚，而不把它们捆成一次“大迁移”。
- 形成明确的 `STAY`、`DEFER` 或 `PROPOSE_NEXT_CHANGE` 结论，以及结论所依赖的证据、未满足条件、风险、所有者和下一步。
- 保证任何迁移/删除都必须继承稳定的调用 Interface、双读/一致性/恢复/观测验证与显式批准。

**Non-Goals:**

- 不实际部署 PostgreSQL、OpenSearch、远程 Lucene、消息队列或多节点服务，不更改 SQLite/WAL/连接/事务配置。
- 不写或改 schema、数据、索引、代码、依赖、MCP/HTTP/CLI、运行时配置、基础设施或遗留 Adapter。
- 不定义未经证据支持的吞吐、延迟、成本或可用性阈值；这些必须基于真实业务 SLO 和工作负载记录。
- 不授权删除 Lucene、SQLite、任何表、索引、Adapter 或依赖；删除永远是获批替代实现验证后的单独 change。

## Design Readiness

**FD_APPLIED**

受管 Feature Design 已将本任务限定为“证据驱动决策”而非“预定迁移”。术语：**trigger** 是已观测且可复现的业务/运行约束，不是预测；**baseline** 是当前部署下可追溯的测量/恢复证据；**replacement** 是已验证能满足同一 Interface 与回滚要求的实现；**legacy removal** 只发生在 replacement 被批准后。

**执行前置 Gate：** foundation 到 MCP 的前序任务必须已产生真实兼容性、恢复、增量索引、Context/MCP 行为和运行观测证据；同时必须存在明确的部署需求或已观测瓶颈。若无 multi-instance/HA/concurrent-write/operational 分离需求及性能/恢复证据，本任务的正确结论是 `STAY` 或 `DEFER`，而不是设计迁移。

该任务的测试 seam 是决策输入/输出的完整性与可追溯性：给定证据包，评审者能验证结论是否引用了触发、基线、约束和回滚条件；它不测试或运行任何新的基础设施。

## Decisions

### 1. 默认保留单实例本地 SQLite + Lucene

在触发条件未满足时，继续使用单实例、同机本地 SQLite 和可重建嵌入式 Lucene BM25。WAL 可以是当前部署的优化手段，但不是跨机共享数据库方案；SQLite 主文件、WAL 和 SHM 必须保持同一主机本地文件系统。这个结论本身是有效决策，不等同于项目停止演进。

### 2. 只有四类证据可触发迁移评审

本 task 只在至少一种有证据的条件出现时评审迁移：

1. 已批准的多个服务实例需要同时写入同一权威知识数据；
2. 已批准的跨节点高可用/故障切换要求不能由单机恢复过程满足；
3. 当前单写队列、短事务和本地优化后仍有可复现的写入/恢复 SLO 失败；
4. 应用与数据库必须分离部署，或嵌入式词法投影的运行/运维成本已被测量为不可接受。

“数据可能增长”“未来也许多用户”或单次临时异常不是 trigger。每个 trigger 都必须关联业务所有者、使用场景、时间范围、负载形状和对当前模型不可满足的具体原因。

### 3. 决策包必须先于技术选择

每次评审收集：当前 deployment topology、读/写并发、数据/索引规模、请求和摄取负载、SLO/实测结果、失败/恢复演练、备份恢复目标、可观测性、成本、运维能力、数据位置/权限约束和回滚要求。基线必须标注采集时间、环境、工具、样本范围和局限。

没有这个证据包不得在 PostgreSQL、特定搜索服务或集群拓扑之间做选择。实际阈值由业务 SLO 决定并记录在此 change 的 decision outcome 中，而不是由代码假定。

### 4. 存储与检索迁移分别决策，Interface 保持调用方隔离

若证据仅证明权威数据存储需要分离，下一 change 可以在已经存在真实实现差异的调用 seam 引入服务器端 `KnowledgeStore` Adapter，并设计受控 dual-read、数据一致性、回填、回滚和切换。它不自动要求替换 Lucene。

若证据仅证明嵌入式 Lucene 的部署/运维是瓶颈，下一 change 可以把 `LuceneBm25LexicalRetriever` 迁移到独立 lexical Adapter；`KnowledgeRetriever` Interface 及调用方不得感知实现拓扑。具体供应商/产品选择不能在本决策任务预设。

### 5. 遗留删除是最后、显式批准的变化

任一旧 Store、Lucene 投影、Adapter、表或依赖只能在以下全部成立后才可能进入独立删除 change：替代实现已验证行为/数据一致性/恢复/可观测性；双读或等效验证完成；回滚窗口和所有者明确；影响方批准；删除目标被精确列出。没有这些条件，本 task 必须选择保留旧路径。

### 6. 结果是明确结论，不是隐式待办

本 change 最终更新 proposal/design/tasks 的 decision record，列出证据、结论、理由、风险、owner、复审日期/触发条件和下一行为：

- `STAY`：当前模型满足已记录需求；
- `DEFER`：证据不足，列出缺口和需要的观察；
- `PROPOSE_NEXT_CHANGE`：触发已满足，只提出一个最小、独立、可回滚的后续 migration 或 cleanup change，尚不实施。

## Recorded Decision (2026-09-21)

- **Outcome:** `DEFER` (the only task-wide outcome).
- **Rationale:** none of the four permitted triggers has approved, attributable evidence. Existing evidence proves bounded local component behavior, not production requirements satisfaction, so it supports neither a definitive `STAY` nor `PROPOSE_NEXT_CHANGE`.
- **Retained architecture:** keep the current single-host SQLite authority path, rebuildable embedded Lucene projection, source/ingestion/retrieval/context/MCP seams, and every legacy rollback path. Storage and lexical decisions remain independent; SQLite main/WAL/SHM remain host-local and are never a network-shared multi-node mechanism.
- **Risks:** deferral may persist if ownership is never assigned; production constraints may remain invisible without telemetry and drills; local fixtures may be overstated; or a deployment may bypass the host-local filesystem constraint. These risks require evidence collection and deployment review, not a speculative technology choice.
- **Owner:** unassigned. Business/SLO, service, data/security, and operations owners must be explicitly appointed before another decision can be approved.
- **Review trigger/date:** recorded on 2026-09-21. Reopen the review when an approved owner supplies evidence for concurrent multi-instance writes, cross-node HA, reproducible current-model SLO/recovery failure after local optimization, or required storage/lexical deployment separation. No evidence supports inventing a calendar review date.
- **Evidence links:** [`tasks.md`](tasks.md) WorkItems 0.1–2.3; [`proposal.md`](proposal.md) decision outcome; [`spec.md`](specs/knowledge-service-migration-decision/spec.md).
- **Next action:** retain current runtime paths and collect the missing production topology/workload, SLO, recovery/backup, telemetry, operations/cost, permissions, ownership, and rollback evidence. Do not create a migration or cleanup change under this outcome.

## Risks / Trade-offs

- [为未来假设过早迁移] → 没有 trigger/baseline 时明确 STAY/DEFER，禁止选择具体服务。
- [只用吞吐指标忽略恢复/运维/权限] → 决策包要求 SLO、恢复、成本、所有者和数据约束共同存在。
- [把存储与搜索绑成高风险大迁移] → 分别评审，保持 `KnowledgeRetriever` 调用隔离。
- [迁移后无法回滚或审计] → 后续 task 必须单独设计 dual-read/一致性/回滚/观测，旧路径保留。
- [过早删除遗留能力] → 删除仅在 verified replacement + explicit approval 后单独进行。
- [网络文件系统误用 SQLite] → 基线和结论重新确认同机本地文件系统限制，跨机需求直接成为迁移评审 trigger。

## Migration Plan

1. 核验执行 Gate；若无 trigger，记录 STAY/DEFER 和下一次需要的证据，不产生实现 work。
2. 收集完整 evidence pack，验证可重复性和当前模型边界，列出风险/限制。
3. 比较仅由证据支持的最小选项，生成明确 outcome；`PROPOSE_NEXT_CHANGE` 时只创建一个相应独立 OpenSpec/AIW change 的建议，不实施。
4. 对任何已批准未来 change，先完成迁移/回滚/验证设计，最后才讨论 legacy cleanup。

本任务自身无运行时回滚；它不改系统。未来 migration 的 rollback 必须在其 own design/spec 中定义并获批准。

## Open Questions

无。当前缺少触发证据时是一个明确的 `DEFER` 结论，不是允许预先选择技术的空白。
