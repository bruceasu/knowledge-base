## Context

前序任务刻意把职责拆开：文档 Adapter 产生结构化文档投影，Ctags 产生符号候选，Git Adapter 产生提交/文件变更证据，SQLite Store 原子保存知识投影。若每个调用方自行拼接这些步骤，就会把 hash、来源、失败恢复和事务细节扩散到同步、检索和未来 MCP 调用方中。本 change 因此引入一个深 Module 来恢复 handoff 中“Extractor → Normalizer → Store → Projection state”的单一入口。

初期没有安全依据将该 Module 自动接入现有文章同步或文件事件流。它只提供显式的应用层调用；现有 `KnowledgeBase`、`KnowledgeRepository`、Lucene、HTTP、CLI、工作区注册和旧表仍保持原有行为。这样既能验证真实知识投影会写入 Store，也不会在未经兼容性设计时造成双写或长事务。

## Goals / Non-Goals

**Goals:**

- 提供一个深 Module `KnowledgeProjectionIngestion`，其 Interface 只暴露 `ingest(IngestionRequest) -> IngestionReport`；调用方不需要了解 Adapter 选择、SQL、hash、事务、stale 标记或工具输出。
- 将已确认的文档、Ctags 符号和 Git evidence 输出规范化为核心对象/证据，并通过 SQLite Store 以每个来源单位为原子单位保存。
- 根据稳定来源身份、输入 content hash 和生成器/profile 版本决定 save、skip-unchanged 或 stale，产生可审计、可重跑的报告。
- 区分一个来源的失败与整个请求的失败：不写半个来源投影，但可让互不依赖的来源在报告中独立成功/失败。

**Non-Goals:**

- 不将 ripgrep 文本命中持久化；它仍是查询期证据候选。
- 不自动注册现有同步、编辑、文件监控、事件总线、定时任务、HTTP/CLI 或 MCP，也不执行 legacy 数据 backfill 或双写。
- 不新增 schema、检索排序、Lucene rebuild、FTS5、语义向量、Python、LLM 推断、关系扩展或通用 plugin framework。
- 不重写旧文章/版本/chunk 或改变其权威性，也不让任意调用方直接调用外部工具或 Store SQL。

## Design Readiness

**FD_APPLIED**

The predecessor-contract gaps discovered during WorkItem 0.1 are resolved by Decisions 7-10
below. The change retains the existing schema and core vocabulary, preserves the existing Ctags
entry point, and adds only the minimum immutable snapshot and atomic Store semantics required by
the ingestion seam.

受管 Feature Design 和 `codebase-design` 已应用。`KnowledgeProjectionIngestion` 是一个 Module，其单一 Interface 是应用/测试的外部 seam；深度来自内部协调多个 Adapter、来源 hash、Store 原子事务、staleness 和报告，而不是向调用方暴露每个步骤。内部可以有测试用 seam，但不新增面向调用方的 Adapter 注册接口。

**执行前置 Gate：** `engineering-knowledge-foundation`、document Adapter、SQLite Store、Ctags 和 Git evidence 任务必须完成，并且各自的实际对象种类、稳定 ID、输入 hash、工具 profile、Store transaction 和错误语义已记录。还必须确认文档/代码/Git source snapshot 的可获得方式；若现有模型无法提供不可变快照，实施前必须先解决而不是在摄取时读取可变运行状态。

最高测试 seam 是 `ingest` 的 `IngestionReport` 与临时 Store 中的最终投影状态。测试只观察保存/跳过/stale/失败结果和证据完整性，不测试内部 SQL 或 Adapter 调用次数。

## Decisions

### 1. 一个深 Module 协调摄取，不把流程分散给调用方

`IngestionRequest` 只描述已验证 workspace、要摄取的受控 source categories、明确 source scope 和资源预算；`IngestionReport` 只返回每个来源的稳定标识、状态（saved/unchanged/stale/failed/unsupported）、对象/证据计数、输入 hash/profile 和结构化诊断。调用方不会得到 Store connection、SQL、Ctags/Git command、文档解析细节或内部 Adapter 句柄。

这一个 Interface 带来调用方的 leverage 和维护者的 locality：后续把现有同步或新工具接到摄取时，只需跨同一 seam，而不复制 provenance/transaction 规则。把 Document、Ctags、Git 和 Store 各暴露为多个应用层方法会形成浅 Module，因此不采用。

### 2. 以不可变 source snapshot 和 profile 为单位规范化

每个摄取来源先生成或接收经验证的不可变 snapshot：稳定来源 ID、content/tree/commit hash、路径/位置、分类所需显式元数据和生成器/profile 版本。随后在内存中完成 Adapter 输出到核心模型的规范化，再交给 Store。

初步分类保持可解释：文档和章节遵循 document Adapter 的 canonical 规则；Ctags 符号是从源文件内容和 Ctags profile 导出的 derived 对象；Git commit 是以不可变 object ID 为依据的 Git 来源对象，文件变更只保留明确的 Git evidence/metadata。若 foundation 的实际 vocabulary 与这些映射冲突，必须更新本设计和核心映射，不能靠字符串或 LLM 猜测类别。

### 3. 每个来源单位原子，跨来源允许可报告的部分完成

一个文档版本、一份确定的 Java 文件/符号集或一个确定的 Git commit selection 是一个来源单位。Module 必须先完成该单位的提取与验证，随后调用 Store 的原子保存；Store 失败时不能留下该单位的半对象、半证据或 completed index state。

不同来源单位不共享同一长事务。一个 Ctags 或 Git 故障不得回滚已经成功保存的独立文档单位；报告必须明确哪些单位成功、跳过、stale 或失败。实现不得在 SQLite 写事务内运行外部工具、解析大型文档或执行 Lucene。

### 4. 使用 hash/profile 驱动幂等和 staleness

Module 比较稳定来源身份、输入 hash 和 Adapter 生成器/profile 版本：完全相同时必须使用 Store 的幂等路径并报告 unchanged；输入或 profile 改变时，保存新投影并让 Store 标记依赖的 derived 状态 stale/retryable。文档正文、Java 文件内容和 Ctags profile 变化必须能使相关符号投影过期；Git commit object ID 一旦写入不可被后续提交改变。

Module 不在这里重建 Lucene。`knowledge_index_state` 只在已提交对象/证据的基础上记录后续可重建工作。

### 5. 证据优先，禁止从适配器输出扩大关系事实

摄取只保存 Adapter 已经提供且满足 core 不变量的对象、来源和证据。它不得因为文档标题、文本命中、Ctags scope 或 Git subject 自动生成 `IMPLEMENTS`、`VERIFIED_BY`、`CALLS` 等关系；若上游提供一个 relation，它仍必须通过 Store 的端点和 evidence 校验。

这样可以让 Context Builder later explain what was observed while保持 “AI 是消费者，不是真相来源” 的原则。

### 6. 初期显式调用，后续再决定运行时触发器

Module 初期仅由受控应用层调用，供测试、手动/内部 re-index 入口或后续实现明确使用。它不注册到文章同步，也不监听事件。等 foundation 的盘点和实际操作需求证明确有可靠触发器、去抖、失败队列和回滚策略后，另一个 change 才可把该 Module 接入现有运行时流。

这避免了不经批准地改变事务边界、引入双写、在用户编辑操作中执行 Ctags/Git 或让索引失败阻塞旧业务。

### 7. Fix source categories, source-unit boundaries, and request budgets

The supported categories are exactly `DOCUMENT`, `JAVA_SYMBOLS`, and `GIT_EVIDENCE`; ripgrep and
unknown categories are reported `UNSUPPORTED` before Store work. One atomic source unit is exactly
one legacy document snapshot, one Java source file from a verified Ctags extraction snapshot, or
one immutable Git commit candidate. Multi-file Ctags and multi-commit Git results are split into
those units before persistence, so one failing file/commit cannot roll back an independent unit.

`IngestionRequest` contains a registered workspace ID and immutable source-unit descriptors, not
SQL, commands, raw Git revisions, or Adapter options. Paths must be normalized workspace-relative
paths already confirmed by their Adapter. Defaults are 64 units and 10,000 total candidate objects;
hard ceilings are 256 units and 50,000 objects. A unit may contribute at most 10,000 objects and
4,096 bounded provenance metadata entries. Exceeding any ceiling fails validation before Adapter
or Store work for that unit.

The per-unit status vocabulary is fixed as follows: `SAVED` means a first/current projection was
committed without invalidating a prior projection; `UNCHANGED` means stable identity, input hash,
and generator/profile version match an existing current marker; `STALE` means a revised projection
was committed and prior input/profile dependents were atomically marked stale; `FAILED` means the
unit did not commit; and `UNSUPPORTED` means its category is outside this Module. A request succeeds
partially when independent units have different outcomes; there is no synthetic global success.

### 8. Bind Ctags candidates to an immutable Java source snapshot without breaking `extract`

The existing `CtagsSymbolExtractor.extract(SymbolExtractionRequest)` contract remains available.
An additive verified entry point returns a `VerifiedSymbolExtraction` containing the normal
`SymbolExtractionResult` plus SHA-256 content hashes for every requested Java file. The extractor
hashes each canonical file immediately before launch and again after parsing; any mismatch returns
a structured source-changed diagnostic and no verified snapshot. Ingestion accepts only this
verified form and splits it into one-file source units.

The canonical Java source object uses `ObjectType.OTHER`, stable versioned ID
`java-source:v1:<length-prefixed workspace-id/path SHA-256>`, and metadata
`engineering.subtype=java-source-file`. Its content hash is the verified file SHA-256. Persisted
symbol IDs use a separate versioned SHA-256 over workspace ID, source path, and the complete
Adapter-local symbol identity; `SymbolIdentity` is therefore input to, but never itself claimed as,
the persistence key. Symbols are derived `CODE_SYMBOL` objects whose input/canonical source is the
Java source object and whose generator version combines Ctags profile ID and effective version.

### 9. Preserve candidate evidence as bounded object provenance, never as a fabricated relation

`knowledge_evidence` remains relation-owned. Because document/symbol/Git candidates do not assert a
relation, ingestion serializes their existing typed evidence into reserved, bounded
`provenance.evidence.*` object metadata fields (type, source system/locator/revision, location, and
confidence). Git file changes use deterministic numbered metadata keys and retain kind, old/new
path, binary flag, and evidence fields. No `RelationProjection` is created unless a future upstream
source supplies an already-valid relation with evidence; this change creates none.

This is an object-provenance representation in the existing schema, not a claim that
`knowledge_evidence` stores object evidence. Rehydrating those fields into first-class `Evidence`
may be added at a later read boundary without schema or relation invention.

### 10. Add atomic profile replacement to the concrete Store without a schema change

`SqliteKnowledgeStore` adds an overload that saves an existing `ProjectionWrite` together with
bounded `ProjectionReplacement` values. The original `save(ProjectionWrite)` remains source/API
compatible and delegates with no replacements. Inside the same transaction, a replacement marks
currently derived objects for the same canonical input and generator ID stale when their generator
version differs from the active version, and marks older current index-state versions for that
projection/input stale; the incoming objects/states are then upserted current. No external tool or
normalization runs in that transaction.

Each source unit writes a current index-state marker keyed by a fixed projection name and the
active generator/profile version. Ingestion uses the existing bounded read methods to recognize an
exact current input-hash/profile marker as `UNCHANGED`. Canonical input hash/revision changes retain
the Store's existing stale behavior; profile-only changes use `ProjectionReplacement`. A committed
replacement that invalidates prior current state reports `STALE`, otherwise `SAVED`.

## Risks / Trade-offs

- [Adapter 输出和 Store schema 映射漂移] → 把 predecessor 的实际 ID/hash/profile 证据设为 Gate，并以 `ingest` end-to-end 夹具覆盖映射。
- [跨来源部分成功难以理解] → 每个来源单位原子，报告明确状态/诊断，绝不伪装全局成功。
- [长解析/外部工具占据 SQLite 锁] → 所有解析和工具调用在 Store 事务外完成，写入保持短小。
- [重复摄取产生重复对象或无效索引] → 稳定 ID + hash/profile 比较 + Store 幂等写入和 state 转换。
- [过早接入现有同步造成双写] → 初期无自动注册；生产触发器单独规划。
- [把工具元数据误作关系事实] → 限制为对象/证据，所有关系继续经过 evidence 规则。

## Migration Plan

1. 核验各前置 Adapter/Store 的完成证据，固定 source snapshot、分类、hash/profile、预算和报告状态。
2. 实现单一摄取 Module、内部源协调和 Store 调用，不修改现有同步/搜索入口。
3. 用小型文档、Java/Ctags JSON、Git evidence 与临时 SQLite fixture 验证保存、unchanged、stale、单来源失败和重开读取。
4. 更新 TODO 和 Verification；后续混合检索仅从已提交 Store 投影和查询期 Adapter 读取，不承担摄取工作。

回滚时停止调用摄取 Module；已写入的新知识表可保留审计/恢复，旧文章与同步路径完全不受影响。

## Open Questions

None. Decisions 7-10 resolve the Java-source mapping, Ctags hash binding, object provenance, Store
profile replacement, source-unit budgets, and report vocabulary. Whether and when this Module
attaches to existing synchronization remains a separate later design question and is not authorized
by this change.
