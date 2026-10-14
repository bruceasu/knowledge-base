## Context

前序 change 已形成两类真实检索来源：结构化 SQLite 知识投影和现有/可重建的 Lucene BM25 词法投影；同时 `rg`、Ctags 和 Git 可提供精确代码、符号和变更证据。因此现在存在多个实际 Adapter，建立 `KnowledgeRetriever` seam 不再是预先抽象，而是把可变实现藏到一个深 Module 后的真实需求。

当前 `LuceneSearchIndex` 仍服务既有搜索路径，SQLite 仍是权威数据源。新检索不能强迫 HTTP/CLI 调用方改用新的查询类型，也不能让调用方学会 Lucene DSL、SQLite 表、ripgrep flags、Ctags JSON 或 Git selection。它只为后续 Context Builder/MCP 提供新的应用层能力。

## Goals / Non-Goals

**Goals:**

- 建立一个深 `KnowledgeRetriever` Module，其 Interface 为 `retrieve(KnowledgeQuery) -> RetrievalResult`，在一个 seam 后完成来源选择、预算、标准化、freshness 校验、去重、融合、排序和诊断。
- 复用 Lucene BM25 作为词法召回及词法候选内的初始排序，不把它升格为权威真相，也不让其原始分数跨来源直接比较。
- 将结构化 Store、可追溯 Lucene、`rg` 精确文本和 Ctags 符号统一为结果项；Git 仅用来丰富匹配对象的来源/近期变更证据。
- 结果保留 canonical/derived 状态、来源、证据、freshness、匹配/排序解释和不完整来源诊断，以支持后续可解释的 Context Builder。

**Non-Goals:**

- 不替换、删除或改变当前 `LuceneSearchIndex`/HTTP/CLI 搜索行为，不修改已有 `SearchHit` 契约。
- 不引入 FTS5、Elasticsearch/OpenSearch、向量/embedding/semantic retrieval、LLM reranking、Python 或新的外部搜索引擎。
- 不改变 SQLite schema、自动摄取触发器、Git/rg/Ctags 工具行为、MCP 工具或任何公开网络接口。
- 不实现完整图遍历、任意 relation 推断、Context token 裁剪或答案生成；这些属于后续 Context Builder。

## Design Readiness

**FD_APPLIED**

受管 Feature Design 和 `codebase-design` 已应用。`KnowledgeRetriever` 是一个 Module，其 Interface 只有一个 query/result 操作；它的 depth 来自对多个候选 Adapter、provenance、staleness、dedupe、tiered fusion 与失败降级的封装。由于现在至少有结构化和 Lucene BM25 两个真实检索实现，并有 `rg`/Ctags 作为更多候选来源，seam 是真实而非假设性的。

**执行前置 Gate：** foundation、document Adapter、SQLite Store、ripgrep、Ctags、Git evidence 和 projection ingestion 均已完成；当前 `LuceneSearchIndex` 的查询、提交、重建和字段能力必须有实际盘点证据；SQLite 投影中必须存在可读的测试对象；`knowledge-base-reliability-performance` 在重叠 Lucene/SQLite 路径上必须已归档或冻结。若当前 Lucene 文档不能关联到稳定 object ID/hash，则实施者必须使用可重建的新增/版本化工程知识投影，不能猜测映射。

最高测试 seam 是 `retrieve` 的外部可见结果集、顺序、provenance、freshness、解释和诊断。内部候选 Adapter 可在 Module 内部替换为夹具，但测试不得断言 Lucene 查询字符串、SQL 或外部命令细节。

## Decisions

### 1. 一个 query/result Interface 隐藏所有检索机制

`KnowledgeQuery` 仅携带已验证 workspace、查询文本、受控对象/来源过滤、结果预算和明确的 freshness/evidence 选项；它不包含 Lucene query、SQL、ripgrep/Ctags/Git 参数。`RetrievalResult` 包含有序 `RetrievalItem`、是否不完整和每个来源的诊断。每个 item 至少带对象或可定位候选身份、匹配摘要、canonical/derived 与 freshness 状态、合并后的来源/证据，以及人可读且机器可测试的 rank explanation。

调用方和测试只跨这一个 Interface。内部可以有 `LuceneBm25LexicalRetriever`、`StructuralRetriever`、ripgrep/Ctags candidate source 和 Git evidence enricher 等 Adapter；它们的不同错误、协议和排序不能泄漏到调用方。这给调用方 leverage，也使排序/freshness bug 具有 locality。

### 2. SQLite 当前投影是权威，Lucene BM25 是可重建词法 Adapter

`StructuralRetriever` 从 Store 读取当前 canonical/derived 对象、已验证关系和 index state。`LuceneBm25LexicalRetriever` 复用 Lucene BM25 进行标题、正文、元数据和可检索代码文本的词法召回，但每个可用 Lucene 文档必须可回溯到稳定 object ID、输入 content hash 和 projection version。

若旧 Lucene 文档缺少这些字段，Adapter 只能返回可由 foundation 映射确认的遗留命中，或使用一份从已提交 Store 可重建的版本化工程知识词法投影；不得把不可映射 hit 当作当前知识。对现有 Lucene 索引的任何字段/投影扩展必须是加法式、可重建、可停用的，且不能改变当前 HTTP/CLI 索引读写语义。

### 3. 候选标准化后采用分层融合，不跨源比较裸分数

Module 先为各来源分配受控候选预算并标准化为共同的候选形态，过滤根外、证据不完整或与请求 workspace 不一致的结果。然后按以下确定性 key 排序：

1. freshness：当前 canonical、当前 derived、stale/retryable；
2. 匹配类别：精确受控标识符/符号、结构化对象匹配、精确文本、BM25-only 词法匹配；
3. 来源完整性：具有可验证 object ID + hash + evidence 的候选优先于不完整候选；
4. 每个来源内部的相关性（BM25 仅在 Lucene 候选中保留其原始相对顺序）；
5. 稳定的对象 ID、相对路径和位置回退。

这样 Lucene BM25 仍提供强大的精确术语、标题和正文召回与初始排序，但它的 score 不会不透明地压过不同量纲的结构化或精确匹配。固定权重、学习排序和 LLM reranking 均不属于本 change。

### 4. 以对象身份和来源位置去重并合并解释

同一当前 canonical 对象、同一 derived 对象或同一稳定 source location 出现在多来源时，Module 必须合并为一个 `RetrievalItem`，保留所有 contributing sources/evidence 和最高适用 rank tier。无法可靠归并的候选不能仅以标题或摘录文本合并；它们必须保持独立并带诊断/限制。

Git evidence 默认只 enrich 已匹配到相同 object/source path 的结果，不作为无查询相关性的独立高分结果。这样近期提交可解释为什么某对象值得关注，但不会让 commit message 主导检索。

### 5. freshness 与投影失败必须对调用方可见

Store 表示 stale/retryable/failed index state 时，Module 应优先当前 canonical 或可验证当前 derived 项。若只有 stale lexical candidate，它不能伪装为当前；按查询选项可作为明确标记的降级候选返回。Lucene 或某个外部候选来源失败时，Module 应在其余来源仍可工作时返回部分结果与 source diagnostic；所有适用来源失败时才返回失败结果。

检索本身不修复索引、不启动 ingestion 或外部工具重建；这些工作分别属于 projection ingestion 或受控运维流程。

### 6. 新 Module 与旧入口并行，安全回滚

初期 `KnowledgeRetriever` 仅由新的应用层消费者调用。现有 HTTP/CLI 继续直接使用当前搜索路径。回滚是停止新调用或将新消费者退回既有 `LuceneSearchIndex` 路径；SQLite 仍为权威，Lucene 投影可从提交状态重建。没有 schema 删除、索引删除或网络部署变更。

## Risks / Trade-offs

- [不同来源的裸 score 不可比较] → 采用 tiered fusion，保留 BM25 仅在本源内的排序，结果附 rank explanation。
- [过期 Lucene 命中被当成当前知识] → 强制 object ID/hash/projection version 对齐，stale 状态显式返回或过滤。
- [去重错合并同名对象] → 仅按稳定对象/来源位置合并，绝不只按 title/摘录。
- [一个 Adapter 故障使全部检索失败] → 受控候选预算和独立 diagnostics，允许其余来源降级返回。
- [新 Lucene 投影影响旧搜索] → 采用 additive/versioned、可重建投影，旧入口不改路由。
- [Interface 变成不同工具参数的集合] → query 只表达检索意图和预算，工具参数全部在 Module 内部。

### 7. Use a separate rebuildable engineering-knowledge Lucene projection

The legacy `LuceneSearchIndex` is package-private to `service` and indexes legacy chunk rows. Its
stored identity fields are numeric `document_id` and `chunk_id`; it has no engineering-knowledge
object ID, input hash, projection version, knowledge lifecycle, or Store input binding. Its query
uses `StandardAnalyzer` plus escaped `MultiFieldQueryParser` text over title/tags/keywords/summary/
content with legacy boosts, optional path-prefix and exact-tag filters, and returns legacy
`SearchHit`. Writes become visible only after explicit `IndexWriter.commit`; rebuild clears and
repopulates from legacy repository documents/chunks, commits once, and rolls back unpublished
mutations on failure.

Those facts make legacy hits intentionally unmappable to current engineering knowledge. This
change SHALL NOT extend or reuse the legacy index. It will add a separately located, disableable,
versioned engineering-knowledge Lucene projection rebuilt only from committed current Store
objects. Every document will store `object_id`, `input_content_hash`, and `projection_version`, plus
workspace/source identity and lifecycle needed for validation; searchable title/content/selected
metadata remain rebuildable. Publication uses one explicit commit after a complete bounded rebuild,
and failed rebuilds retain the previous committed reader. Retrieval never starts a rebuild.

### 8. Fix retrieval budgets, freshness, and rollback routing

One query defaults to 20 returned items and has a hard caller-visible ceiling of 100. Internal
candidate ceilings are 200 Store objects, 200 Lucene hits, 100 ripgrep matches, 100 Ctags symbols,
and Git enrichment for at most 64 already-matched source paths; an overall normalized-candidate
ceiling of 500 prevents source multiplication. The query deadline defaults to 5 seconds and has a
hard configuration ceiling of 30 seconds. Existing Adapter ceilings remain authoritative and the
retrieval Module may only request equal or smaller work.

Freshness order is current canonical, current derived whose input hash/profile still matches,
then explicitly requested stale/retryable candidates. Stale candidates are excluded by default;
failed or unmappable projections are diagnostics, never current results. A source that reaches its
budget reports truncation, while source failure permits partial results when another source
succeeds.

`knowledge-base-reliability-performance` is archived at
`openspec/changes/archive/2026-09-19-knowledge-base-reliability-performance`, so it no longer owns
overlapping SQLite/Lucene edits. Rollback disables the new `KnowledgeRetriever` consumer and the
separate engineering index directory; it never deletes or rewrites the legacy index or Store.
Failed engineering-index rebuilds discard unpublished changes and retain the last committed reader.
Retrieval does not trigger ingestion or rebuild.

## Migration Plan

1. 核验前置 Gate，记录现有 Lucene/Store 字段、索引重建方式、对象映射和每个来源的预算/失败语义。
2. 定义 `KnowledgeQuery`/`RetrievalResult` 和内部候选 contracts，在夹具上实现标准化、freshness、去重、tiered ranking 与解释。
3. 实现可回溯 Lucene BM25 Adapter；必要时建立独立、加法式、可从 Store 重建的工程知识投影，不改变现有 HTTP/CLI 路径。
4. 接入结构化、`rg`、Ctags 与 Git enrichment，并用固定集合验证降级、排序、过滤、staleness 和恢复状态。
5. 更新 TODO/Verification；Context Builder 是第一个新消费者，MCP 后续只调用 Context Builder/应用层，不触碰底层 Adapter。

回滚时停用 `KnowledgeRetriever` 的新消费者或新词法投影，不删除旧索引/表；既有搜索继续使用原行为。

## Open Questions

无。具体 Lucene 字段和 projection rebuild 细节必须由前置的实际索引盘点确定；若它们无法满足 object ID/hash/version 的可追溯性，应先完成版本化投影设计而非在融合层放宽 freshness 规则。
