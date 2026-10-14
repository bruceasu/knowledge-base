## Why

SQLite 与嵌入式 Lucene 适合当前单实例、本地磁盘、单写入协调的部署目标；只有真实的多节点、高可用、并发写入或运维瓶颈才证明需要服务端数据库或独立搜索形态。将迁移和遗留删除变成证据驱动的决策任务，可避免为了“将来可能需要”而过早破坏可回滚的本地优先架构。

## What Changes

- 定义服务化/迁移决策的触发证据、基线、成功指标、风险评估、所有者和明确的结论记录，而不是预先选择 PostgreSQL、OpenSearch 或删除 Lucene。
- 评估三种结论：继续单实例 SQLite + Lucene；仅在证据支持时规划服务器端 `KnowledgeStore` Adapter；仅在证据支持时规划独立/替代的 lexical retrieval Adapter。
- 规定任何双读、迁移、回滚、观测、数据一致性或遗留删除必须在单独获批的实现 change 中进行，且旧 Adapter 必须保留到验证完成。
- 不在本 change 修改 schema、代码、依赖、部署、索引、数据、HTTP/CLI/MCP 或运行时配置；它只生成受证据约束的架构决策与下一步。

## Decision Outcome (2026-09-21)

**Outcome: `DEFER`.** No approved multi-instance-write, cross-node-HA, reproducible post-optimization SLO/recovery-failure, or required deployment-separation trigger is recorded. The repository provides focused local component compatibility and recovery evidence, but no attributable production topology/workload baseline, business SLO, restore/failover drill, longitudinal telemetry, operating-cost record, permission baseline, or accountable owner. That evidence is insufficient both to prove a production `STAY` conclusion and to justify `PROPOSE_NEXT_CHANGE`.

The current single-host SQLite and embedded Lucene paths, all Adapters, schemas, indexes, dependencies, and runtime contracts remain unchanged. No server database, external search product, migration, or cleanup change is proposed. The decision risks are indefinite deferral without ownership, treating component fixtures as production proof, and attempting network-shared SQLite/WAL/SHM; the recorded Gates explicitly prohibit those interpretations.

Owner status: no accountable business/SLO, service, data/security, or operations owner is recorded; appointing those owners is an unmet Gate, not an inferred assignment. Review record date: 2026-09-21. Review again when at least one approved trigger is documented with an owner and complete evidence pack; no unsupported calendar deadline is invented. Next action: retain the current paths and collect the missing production topology, workload/SLO, recovery, telemetry, cost, permission, ownership, and rollback evidence. Evidence: [`tasks.md`](tasks.md) WorkItems 0.1–2.3 and [`spec.md`](specs/knowledge-service-migration-decision/spec.md).

## Capabilities

### New Capabilities

- `knowledge-service-migration-decision`: 以运行与运维证据决定是否触发工程知识平台的服务端存储、检索部署或遗留清理变更。

### Modified Capabilities

- 无。

## Impact

本变更是规划/决策任务，不含实现代码或生产配置改动。它依赖前序知识存储、检索、上下文与 MCP 能力的兼容性、恢复和观测证据；其输出将决定后续是否创建独立的 PostgreSQL、搜索部署或遗留删除任务，而不是直接改变现有 SQLite/Lucene 路径。
