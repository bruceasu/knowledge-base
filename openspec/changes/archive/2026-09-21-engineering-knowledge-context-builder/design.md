## Context

`KnowledgeRetriever` 统一了结构化、BM25、文本、符号和 Git 候选，但它的结果仍需要由调用方决定“哪些先放、关系扩展到哪、如何在 token 预算内裁剪”。这种编排若散落在每个 AI 代理或将来的 MCP 工具中，排序、freshness 和 provenance 会失去一致性。因此 Context Builder 是工程知识平台交付给调用方的主要深 Module。

本 change 仅消费应用层 `KnowledgeRetriever` 与受控的 evidence-bearing relation read seam；它不直接查询 SQLite、Lucene、ripgrep、Ctags 或 Git。它也不生成答案、摘要或事实推断：输出是结构化 `EngineeringContext` 数据包，调用方可以按需要渲染，但每一片内容都可回溯。

## Goals / Non-Goals

**Goals:**

- 提供一个深 `ContextBuilder` Module，其 Interface 只有 `build(ContextRequest) -> EngineeringContext`；Interface 包含 workspace、目标/意图、正 token budget 和受控 freshness/evidence 选项，不暴露检索、图或存储参数。
- 在内部定位目标，沿有证据的允许关系作固定范围扩展，收集相关需求/设计/决策、代码符号、测试和 Git evidence，优先当前 canonical 项。
- 用版本化、确定性的 token 估算和装配策略，在预算内输出对象片段、来源、置信度、freshness、关系理由、总估算和 omission records。
- 对空结果、检索/关系读取不完整、stale 降级、预算不足和内容截断产生可检查的诊断，而不悄悄补写文本。

**Non-Goals:**

- 不调用 LLM、embedding、任何 tokenizer SaaS 或生成摘要/答案/关系；不从自然语言猜测关联。
- 不进行无限/任意图遍历，不新增关系类型，不修改 relation/evidence 事实，也不在 Context Builder 中摄取或重建投影。
- 不直连或泄漏 SQLite、Lucene、rg、ctags、Git、SQL/CLI 参数；不改 HTTP/CLI/MCP、schema、当前搜索/同步、依赖或部署配置。
- 不在此阶段实现认证/授权传输层；它只处理已验证本地 workspace，后续 MCP exposure 必须单独处理对外信任边界。

## Design Readiness

**FD_APPLIED**

受管 Feature Design 和 `codebase-design` 已应用。`ContextBuilder` 是一个 Module，唯一外部 Interface 是 `build`，也是所有测试的主 seam。它的 depth 来自内部检索、受限关系扩展、freshness 筛选、去重、预算装箱、截断和 omission/explanation；这为调用方提供 leverage，并将上下文选择缺陷集中到一个位置获得 locality。内部的 relation reader/token estimator 可以是测试 seam，但不是调用方要学习的 Interface。

**执行前置 Gate：** hybrid retrieval、projection ingestion 和 Store 的实际对象/关系/evidence/freshness read 语义必须完成；relation read seam 必须能够按稳定对象 ID 读取有证据的允许关系及当前状态；需要固定 token-estimator 版本和最小可用 token budget。若 Store 无法区分 evidence-bearing relation 和无来源候选，实施前必须补齐而不是扩展图遍历。

最高测试 seam 是 `build` 返回的 `EngineeringContext`：测试断言其项目、顺序、估算预算、provenance、stale 标志、截断与 omitted reasons，而不观察 SQL、Lucene、Adapter 调用顺序或内部 token 计算细节。

## Decisions

### 1. 一个小 Interface 隐藏检索、关系和预算复杂度

`ContextRequest` 只接收已验证 workspace、目标描述、受控意图类别、正 token budget 以及显式的 freshness/evidence 选项。`EngineeringContext` 返回有序 context items、目标/intent、总估算 token、估算器版本、complete/incomplete 标记、diagnostics 和 omission records。每个 item 还要带对象/来源 ID、内容 fragment、内容范围、canonical/derived/freshness、confidence、contributing evidence 和选择理由。

调用者既不传 relation depth、Lucene score、SQL 过滤器，也不分别调用 `rg`/Ctags/Git。固定的一跳 evidence-bearing relation expansion 和内部预算策略在 Module 内部实现；需要更大图探索时另建有资源与安全约束的 change。这样避免把“构建上下文”退化成向调用方公开工具参数的浅 Interface。

### 2. 只使用当前、可追溯的知识和有证据关系

Builder 先调用 `KnowledgeRetriever` 识别目标候选，再通过受控 relation reader 读取与目标关联的一跳关系。只允许 core 已确认且带 Evidence 的关系进入扩展；没有 evidence、workspace 不一致、对象缺失或无效 source 的边必须跳过并作为诊断保留。

默认优先 current canonical，再是 current derived。stale/retryable/failed 投影默认不包含；只有请求明确允许且没有可用当前替代时，才以清晰 stale 标记和降级原因纳入。Git evidence 只附着在有相同稳定对象/来源路径的 item 上，不能凭提交文字创造关联。

### 3. 用固定优先级和稳定键组织工程语境

经检索和关系扩展后的候选按固定、可解释优先级选择：目标本身；有明确证据的 requirement/design/decision；与目标直接相关的当前代码符号；对应测试；匹配源路径的近期 Git evidence；其余当前相关项。相同层内复用 hybrid retrieval 的解释性顺序并以对象 ID/来源位置稳定 tie-break。

同一对象或同一稳定来源位置的候选在装配前去重并合并 evidence；同名但身份不同的对象不可仅凭标题合并。选择理由记录为元数据，不用 LLM 改写原始内容。

### 4. 预算是确定性估算，不是假装模型精确 token 数

Context Builder 使用本地、版本化、确定性的 conservative token estimator，对每个 item 的统一渲染形式估算成本。`EngineeringContext` 必须返回 estimator version、每项估算和总估算；它不承诺等同任一模型的 BPE token 数。估算规则变更必须版本化，避免同一输入在无记录的情况下改变预算结果。

装配先保留目标的最小来源可定位 header，再按优先级加入完整 item。若完整内容不适合剩余预算，可使用从原始内容按明确位置规则截取的 fragment，并标记 `truncated` 和原始范围；不得生成抽象摘要。未入选或被截断的项必须在 omission records 中说明是 budget、stale、缺失 evidence、source failure 还是重复合并所致。小于最小目标 header 的预算必须被拒绝为无效请求。

### 5. 失败和不完整性通过 context 明示，而非伪造完整包

retriever 或 relation reader 的 source diagnostics 进入 `EngineeringContext`。若目标无法定位，返回空 context 和可读诊断；若可定位但某来源失败，返回可用 items，标记 `incomplete` 和缺失来源。Builder 不触发 ingestion、索引重建、外部工具或重试写入。

### 6. 先作为内部应用层能力交付

初期只有受控应用层调用 `ContextBuilder`。现有 HTTP/CLI 继续原样，MCP 在后续 change 仅作为调用此 Module 的 transport Adapter。回滚时停止新调用；没有数据写入、索引更改或公开契约撤销。

### 7. Fixed context policy vocabulary and deterministic rendering

Implementation uses the following closed `ContextIntent` vocabulary: `UNDERSTAND`, `CHANGE`, `REVIEW`, `DIAGNOSE`, and `TEST`. Callers cannot supply free-form intent names. Intent is explanatory request metadata in this change; it does not weaken evidence, freshness, relation, ordering, or budget rules.

Relation expansion accepts only `REFINES`, `DESCRIBES`, `IMPLEMENTS`, and `VERIFIED_BY`. `RELATED_TO` is excluded because it does not state a sufficiently precise engineering relationship. Starting from the resolved target object, the Builder reads relations touching that target in either direction, keeps only relations with at least one evidence item whose confidence is at least `0.5`, resolves the opposite endpoint in the same workspace, and never reads relations from an expanded endpoint. The expansion limit is therefore exactly one hop. Missing endpoints, unsupported relation types, insufficient evidence, workspace mismatch, and relation-read failure are omitted or diagnosed; they are never inferred or traversed.

The estimator version is `utf8-bytes-v1`. The estimate of an item is the UTF-8 byte length of its complete rendered form, with one estimated token per byte; the context total is the exact sum of item estimates. This intentionally conservative local estimate is deterministic and makes no model-tokenizer accuracy claim. The minimum request budget is `256` estimated tokens.

Each item is rendered with normalized `\n` line endings as a fixed header followed by unmodified source content: `type=<ObjectType> id=<object-id-or-dash> source=<system>:<locator> revision=<revision-or-dash> range=<start-or-dash>:<end-or-dash>`, then one newline, then the content. Full content is preferred. If it does not fit, truncation retains the complete header and the longest content prefix that fits, cuts only at a Unicode code-point boundary, preserves the original source range, and sets `truncated=true`; it does not add generated prose. If the header cannot fit, the item is omitted. The target header is reserved first, which is why budgets below 256 are rejected before retrieval.

The closed `OmissionReason` vocabulary is `BUDGET`, `STALE`, `MISSING_EVIDENCE`, `LOW_CONFIDENCE_EVIDENCE`, `DUPLICATE_MERGED`, `SOURCE_FAILURE`, `INVALID_SOURCE`, `MISSING_OBJECT`, `UNSUPPORTED_RELATION`, and `WORKSPACE_MISMATCH`. A truncated item also produces a `BUDGET` omission record for its omitted remainder. Diagnostics describe request-wide or source-operation failure; omission records describe candidate-level exclusion or merging.

## Risks / Trade-offs

- [token 估算与特定模型真实 token 不同] → 使用保守、版本化估算并返回版本/估算，MCP/模型适配另行处理。
- [关系扩展导致内容爆炸或噪声] → 固定一跳、只接收 evidence-bearing relation、严格优先级/预算和 omission record。
- [stale 投影误导代理] → 默认排除，只有请求显式允许且标记降级时才返回。
- [budget 截断破坏可理解性] → 保留对象/source header、明确原始范围与 truncation，绝不 LLM 摘要。
- [不同调用方自行重排上下文] → 单一 `build` Interface 提供统一选择理由和稳定顺序。
- [未来对外暴露绕过权限] → 本 change 不提供 transport；MCP change 必须在入口验证权限/工作区访问。

## Migration Plan

1. 验证前置 Gate，固定 relation-read 行为、允许关系集合、intent vocabulary、token estimator version 和最小预算。
2. 实现 `ContextRequest`/`EngineeringContext`、内部检索/关系协调、current/stale 筛选、去重、排序、预算装配和 diagnostics；不注册运输入口。
3. 用固定知识图/检索夹具验证目标定位、关系扩展、去重、顺序、预算截断、omission、stale 降级和部分来源失败。
4. 更新 TODO/Verification；后续 MCP 将调用该 Module，不会重新实现上下文选择。

回滚时停用 Context Builder 的新消费者；它不会写 Store、Lucene、旧表或更改当前 HTTP/CLI 行为。

## Open Questions

无。若后续某个模型需要精确 tokenizer 预算或多跳 trace，必须新增带模型/资源边界的 change；不得在这个确定性本地 Module 中隐式依赖模型服务。
