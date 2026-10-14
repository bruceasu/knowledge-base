## ADDED Requirements

### Requirement: Evidence-gated migration review

The system's architecture review SHALL consider service migration only when it has documented evidence of at least one approved trigger: required concurrent multi-instance writes, required cross-node high availability, reproducible current-model write/recovery SLO failure after local optimization, or a required application/database or lexical-deployment separation. Speculation about future scale alone MUST NOT trigger a technology selection.

#### Scenario: No approved trigger is present

- **WHEN** the review has no documented multi-instance, HA, SLO, or deployment-separation trigger
- **THEN** its outcome is `STAY` or `DEFER` and it does not propose PostgreSQL, an external search service, or legacy deletion

#### Scenario: Concurrent multi-instance writes are approved

- **WHEN** an approved deployment requirement documents multiple service instances writing the same authority data with workload and owner evidence
- **THEN** the review may produce `PROPOSE_NEXT_CHANGE` for a separately scoped server-side storage migration design but does not implement it in the review

### Requirement: Complete and traceable decision evidence pack

Before selecting an option, the review MUST record current deployment topology, local filesystem/WAL assumptions, read/write concurrency, data/index scale, workload/SLO evidence, failure/recovery and backup evidence, observability, operations/cost constraints, data/permission constraints, rollback requirements, evidence collection time/environment/tool/sample limits, and accountable owner.

#### Scenario: A performance claim lacks recovery evidence

- **WHEN** an evidence pack reports throughput or latency concerns but omits failure/recovery and rollback information
- **THEN** the review records the missing evidence and returns `DEFER` rather than treating a specific storage migration as approved

### Requirement: Preserve the local-first default safely

Until a migration outcome is explicitly approved, the architecture MUST retain the single-instance Java service, same-host local SQLite file model, short transactions, coordinated writes, and rebuildable Lucene BM25 lexical projection. SQLite, WAL, and SHM files MUST NOT be treated as a multi-node shared network-filesystem database.

#### Scenario: A team proposes sharing the SQLite file over a network filesystem

- **WHEN** a proposed deployment would allow multiple nodes to access the same SQLite/WAL/SHM files over NFS, SMB, EFS, or equivalent network storage
- **THEN** the review classifies it as a migration trigger/risk and does not approve it as continuation of the local SQLite deployment model

### Requirement: Separate storage and lexical migration decisions

The review MUST evaluate authority-storage migration and lexical-retrieval deployment separately. A storage trigger MAY lead only to a future `KnowledgeStore` Adapter migration design; a lexical deployment trigger MAY lead only to a future lexical Adapter design that preserves `KnowledgeRetriever` for callers. Neither decision MUST implicitly require replacing the other implementation.

#### Scenario: Only the embedded lexical deployment is an operational bottleneck

- **WHEN** evidence shows that Lucene deployment/operations is the bottleneck while SQLite authority storage meets requirements
- **THEN** the review proposes at most a separately scoped lexical Adapter change and keeps the SQLite migration out of scope

### Requirement: Explicit outcome and next-action record

The review MUST record exactly one outcome: `STAY`, `DEFER`, or `PROPOSE_NEXT_CHANGE`. The record MUST include supporting evidence, rationale, risks, owner, review condition/date, unfulfilled gates, and next action. `PROPOSE_NEXT_CHANGE` MUST name one minimal independent change and its required migration/rollback/validation design; it MUST NOT begin implementation.

#### Scenario: Evidence supports an eventual storage migration

- **WHEN** the evidence pack satisfies a storage trigger and identifies a real operational need
- **THEN** the review records `PROPOSE_NEXT_CHANGE` with a minimal storage-migration proposal and required dual-read, consistency, recovery, observability, rollback, and approval gates without changing code or deployment

### Requirement: Legacy removal remains a final approved action

The review MUST NOT delete or approve deletion of a legacy Store, Lucene projection, Adapter, table, index, or dependency unless a verified replacement, behavior/data consistency evidence, recovery evidence, observability evidence, rollback window, responsible owner, and explicit approval all exist. Any deletion MUST be a separate change after those gates pass.

#### Scenario: A replacement has not completed consistency verification

- **WHEN** a proposed replacement has not completed its required consistency or recovery validation
- **THEN** the review retains the legacy path and records legacy cleanup as deferred rather than proposing deletion

### Requirement: No runtime modification in the decision task

This decision task MUST NOT modify schema, code, dependencies, data, indexes, deployment, configuration, HTTP/CLI/MCP, or legacy runtime behavior. Its deliverable is only the evidence-backed decision record and next-action recommendation.

#### Scenario: Complete a `STAY` review

- **WHEN** the evidence concludes that the local-first model continues to meet documented requirements
- **THEN** the task updates its decision record without creating a migration, changing a configuration, or modifying a runtime path

### Requirement: Recorded 2026-09-21 decision

The decision record SHALL have exactly one current outcome: `DEFER`. It SHALL retain the current runtime paths because no approved migration trigger or complete production evidence pack exists, while also acknowledging that missing production requirements evidence is insufficient to assert `STAY`. The record SHALL identify the owner as unassigned, require explicit business/SLO, service, data/security, and operations ownership before approval, and require review when an approved trigger and complete evidence pack become available. It MUST NOT propose a migration or cleanup change under this outcome.

#### Scenario: Current evidence remains incomplete

- **WHEN** no approved trigger, accountable owner, production SLO/baseline, recovery drill, telemetry/cost record, and rollback evidence are available
- **THEN** the decision remains `DEFER`, all current Store/Lucene/Adapter and legacy paths are retained, and the next action is evidence collection rather than technology selection
