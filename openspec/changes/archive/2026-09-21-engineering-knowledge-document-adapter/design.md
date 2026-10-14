## Context

`engineering-knowledge-foundation` 先盘点并定义独立于基础设施的知识核心。本变更是其后的反腐层：它读取现有 `Article`、`ArticleRevision`、文档元数据和已有 `Chunk` 投影，产出面向工程知识模型的结构化文档视图，而不让旧实体直接实现新核心类型。

现有文档生命周期、版本、编辑、同步、SQLite 持久化和 `LuceneSearchIndex` 都是兼容性边界。它们在本变更中只能作为输入证据，不能被 Adapter 反向改变。此阶段也尚未有新的权威知识表；Adapter 的输出仅在内存中验证，后续 `engineering-knowledge-sqlite-store` 才决定如何持久化。

## Goals / Non-Goals

**Goals:**

- 提供一个深而窄的 `LegacyDocumentKnowledgeAdapter` seam：调用者提供一个经解析的旧文档快照，得到一个完整、可追溯的 `DocumentKnowledgeProjection`，而不是分别调用“取标题”“切 chunk”“算 hash”等基础设施细节。
- 将文档根节点和 Markdown/OpenSpec 标题层级表示为核心知识对象，并保留每个对象的稳定身份、内容 hash、来源位置和父子结构。
- 明确 canonical 文档/章节与 derived chunk 的边界；derived 数据必须能追溯到输入对象与输入 hash。
- 用可重复的小型夹具锁定身份、层级、来源、重复标题、空文档和无标题文档等外部可见映射行为。

**Non-Goals:**

- 不新增或修改 SQLite 表、迁移、事务边界、`KnowledgeRepository` 或旧文章/版本/chunk 数据。
- 不接入现有摄取、同步、编辑、搜索、Lucene、HTTP、CLI、MCP 或上下文构建生产路径。
- 不新建 `KnowledgeStore`、`KnowledgeRetriever` 或搜索实现；不引入 FTS5、Python、Tree-sitter、LSP、ctags、Git 或新的第三方 Markdown 依赖。
- 不将标题或正文的启发式/LLM 推断写成需求、设计或实现关系事实。

## Design Readiness

**FD_APPLIED**

术语与边界已按受管 Feature Design 审视：`Article`/`ArticleRevision` 是旧业务模型，`DocumentKnowledgeProjection` 是 Adapter 输出，`KnowledgeObject` 是存储无关的核心模型，固定 token chunk 是 derived projection 而非文档主结构。没有仓库级 `CONTEXT.md`、`CONTEXT-MAP.md` 或 ADR 可覆盖这些术语。

**执行前置 Gate：** `engineering-knowledge-foundation` 的 1.1–1.3 与 2.1–2.3 必须已完成，并在其盘点中给出实际的文章身份、版本、内容、路径/来源、chunk 与核心术语映射证据。未满足时，本 change 可以保留为已审阅的规划，但不得交给 `/implement`，更不得猜测旧模型的稳定 ID 或版本语义。

最高测试 seam 是 Adapter 的公开映射操作：给定同一份旧文档快照，调用者只观察 `DocumentKnowledgeProjection` 的文档、章节、派生 chunk 引用和诊断结果；测试不依赖 SQLite、Lucene、HTTP 或真实外部文件系统。

## Decisions

### 1. 使用只读反腐层，而非改造旧实体

`LegacyDocumentKnowledgeAdapter` 是唯一的文档映射入口。它接收一个由旧模型提取的、包含稳定来源身份、版本内容、显式来源元数据和既有 chunk 信息的快照，并返回一个 `DocumentKnowledgeProjection`。旧 `Article`、`ArticleRevision` 和 `Chunk` 不实现核心接口，也不承担新对象的生命周期。

这样可以保留既有编辑与同步语义，并使后续 SQLite Store、检索和 Context Builder 都依赖稳定的投影结果，而不是依赖旧服务类。直接让旧实体实现新核心类型会把版本、持久化和搜索职责泄漏到目标领域模型中，因此不采用。

### 2. 文档与章节优先于固定大小 chunk

投影必须包含一个文档根对象，以及按标题层级生成的章节对象。章节的逻辑父子关系由投影结构显式表达，并以来源范围、`headingLevel`、标题路径和同级重复序号支持追溯；不得滥用 `RELATED_TO` 伪装结构包含关系，也不得为此阶段扩张基础关系词汇。

既有固定 token chunk 可以作为 derived 条目被携带，但每一项必须带有其来源文档/章节身份（可用时）、生成器信息和输入内容 hash。它不得取代章节作为默认语义单元，也不得被重新写入旧表。

### 3. 身份稳定性以经盘点确认的来源为根

文档 canonical ID 必须取自 foundation 盘点确认的稳定来源身份，而不能仅由标题、绝对本机路径或正文 hash 推导。正文变化后的同一文档必须保持同一 canonical ID；版本内容与其 hash 则作为来源/投影状态的一部分保留。

章节 ID 必须由文档 canonical ID、规范化标题路径和能够区分重复标题的确定性序号组成。单纯正文变动不应改变未移动章节的身份；同名章节不得碰撞。具体编码格式只在 foundation 的实际 ID/元数据约定确认后实现，且必须在本 change 的验证记录中固定下来。

### 4. 结构解析必须有界且可诊断

初始 Adapter 只支持经夹具和仓库盘点确认的 Markdown 标题语法及 OpenSpec 的 Markdown 标题结构。它必须忽略代码围栏内看似标题的文本，并对跳级标题、重复标题、缺少标题、空内容和不能识别的构造给出确定性结构或明确诊断；不得静默丢弃正文。

OpenSpec 工件的类别只能由明确的来源元数据或已确认的路径/文件名规则决定。正文词汇、标题名称或 LLM 推测不能自动升级为 requirement、design 或 decision 等事实分类。

### 5. 复用核心的来源与 hash 规则，不创建第二套真相

Adapter 必须使用 foundation 定义的 `SourceRef`、canonical/derived 语义和内容 hash 规则。若旧模型没有足够的路径、版本或范围信息，Adapter 应在投影诊断中显式标为不可完整定位，而不是伪造来源或 100% 置信度。

本阶段不持久化投影、不建立索引、不改变投影状态。后续 Store 将以该输出和实际数据模型为输入设计自己的幂等写入与过期策略。

## Confirmed implementation contract

The foundation inventory resolves the Adapter's input and identity policy as follows.

- `LegacyDocumentKnowledgeAdapter` accepts an immutable snapshot containing the existing `Article`, its current revision content, existing `Chunk` values, and caller-supplied source context. It does not modify those legacy values or register itself with any existing application path.
- `ArticleId` is retained only as `legacy.article-id` trace metadata. The caller MUST supply a scoped, durable `stableSourceId`; the Adapter derives the canonical document ID solely from that opaque identity. It MUST NOT derive the ID from the SQLite article ID, a local source path, title, or content hash.
- A successful knowledge projection requires a logical source system and locator. When either is absent, the Adapter returns a diagnostic-only projection with no `KnowledgeObject`, rather than inventing a `SourceRef`. Source revision remains optional because the core permits it to be optional.
- The document object's content hash is the current legacy `ArticleRevision.contentHash` without recomputation. That legacy value describes the raw source bytes, whereas Java revision content is decoded text. Section and derived-chunk object hashes are SHA-256 hashes of their UTF-8 text slices; their metadata records this distinct scope.
- The supported structural grammar is ATX Markdown headings (`#` through `######` followed by whitespace and text) outside fenced code blocks delimited by backticks or tildes. Setext headings and malformed ATX forms are retained as text and reported as diagnostics. A skipped heading level attaches to the nearest less-deep heading (or the document root) without synthesizing missing levels, and emits a diagnostic.
- Every existing `Chunk` becomes a derived projection entry. The Adapter is the recorded generator of that projection (`legacy-document-knowledge-adapter`, contract version `1`); any known legacy chunk-generator identity is copied to metadata. When the legacy generator is unavailable, the Adapter emits a diagnostic instead of claiming a version for `TextChunker` or another historical generator.
- OpenSpec classification may come only from explicit snapshot metadata or these repository-confirmed relative-path rules: `openspec/**/specs/*/spec.md` is `REQUIREMENT`, `openspec/**/design.md` is `DESIGN`, and `openspec/**/.openspec.yaml` is `CONFIGURATION`. Explicit metadata wins over a conflicting path rule and the conflict is diagnosed.

## Risks / Trade-offs

- [旧文章身份与目标 canonical ID 不一致] → 以 foundation 的实际盘点为硬 Gate；未确认前不编码 ID 公式。
- [Markdown 方言或异常标题破坏层级] → 明确受支持语法，使用夹具覆盖边界并通过诊断保留不可识别情况。
- [重复标题导致章节碰撞] → 使用标题路径加确定性重复序号，而不是标题文本本身。
- [chunk 被误当成主知识单元] → 在输出模型和测试中明确标记为 derived，并要求 provenance 与输入 hash。
- [Adapter 侵入旧路径] → 不注册到现有同步/搜索入口；只进行独立构造与单元测试。
- [为解析引入高风险依赖] → 初始实现复用现有能力或采用小而受限的解析逻辑；任何新增依赖必须另行获得批准。

## Migration Plan

1. 核验 foundation 的盘点与核心测试证据，确认输入快照字段、对象类型和来源约定。
2. 实现独立 Adapter 与映射结果值类型，以固定夹具验证输出，不注册到生产服务。
3. 静态检查旧文章、版本、chunk、SQLite、Lucene、HTTP/CLI、依赖和配置均未被修改。
4. 更新本 change 的 TODO、Verification 和仍需后续 Store 决定的事项；将持久化留给 `engineering-knowledge-sqlite-store`。

回滚只需停止调用或移除新 Adapter；由于本变更不写入新旧数据、不修改索引且不改变公开入口，既有文档功能不受影响。

## Open Questions

无。与现有模型具体字段相关的事实由上述 foundation 前置 Gate 解决；若盘点结论与本设计的稳定身份、版本或来源假设冲突，实施者必须回到本 change 更新设计，而不是在代码中自行选择另一套语义。
