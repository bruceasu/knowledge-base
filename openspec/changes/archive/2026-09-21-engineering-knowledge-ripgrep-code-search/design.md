## Context

文本搜索是代码知识的最低层、最确定性的证据来源：它可以说明某个字符串出现在某个受限工作区位置，却不能单独说明需求被实现、方法被调用或设计被遵循。`rg` 已适合本地精确搜索，但其可执行文件、参数、工作目录、退出码、编码和输出边界不能泄漏给后续检索和 Context Builder 调用方。

本 change 位于 `engineering-knowledge-sqlite-store` 之后，复用核心的 `SourceRef`/Evidence 术语，但不把搜索结果写入 Store。现有 Lucene BM25 搜索和 HTTP/CLI 入口均不是本次集成点；后续 `engineering-knowledge-hybrid-retrieval` 才组合多个候选来源。

## Goals / Non-Goals

**Goals:**

- 提供一个 `search(CodeSearchRequest) -> CodeSearchResult` 深 Module，让调用方只描述受限工作区内的精确查找意图与结果预算。
- 返回工作区相对、可验证的文本命中，包含路径、行列范围、受限摘录、查询匹配信息、核心来源/证据和诊断。
- 以不经过 shell 的参数化进程调用、已验证根目录、固定字符串模式、超时/输出/结果限制和确定性排序控制外部命令风险。
- 区分正常无命中、工具不可用、非预期退出、超时、解析失败和结果截断；任何不完整结果都必须对调用方可见。

**Non-Goals:**

- 不提供任意 ripgrep 参数、任意 regex、任意命令、工作区外路径或直接 shell 执行能力。
- 不做 ctags 符号提取、调用/引用图、Git 历史、Tree-sitter、LSP、LLM 关系推断或 Python 集成。
- 不把文本命中持久化为 canonical 事实、关系或 Lucene 文档，不修改 SQLite schema、现有搜索入口或 HTTP/CLI。
- 不建立覆盖所有外部工具的通用执行框架；只在 Adapter 自身需要处使用最小进程边界。

## Design Readiness

**FD_APPLIED**

受管 Feature Design 已审视外部进程、路径边界、结果完整性和证据语义。`CodeSearchRequest` 表示调用意图，不携带原始命令；`CodeSearchResult` 表示候选和诊断；文本命中是来源证据，绝不是工程关系事实。

**执行前置 Gate：** foundation、SQLite Store 已完成；foundation 盘点已确认工作区根目录注册/验证方式、配置加载惯例和现有外部进程 seam（如有）。实施者还必须记录目标运行环境中 `rg` 的受支持版本/发现策略和不可用时的明确行为。未满足时不得把 Adapter 注册到任何入口。

最高测试 seam 是公开 `search` 操作及其返回的排序、命中、来源和诊断。进程输出解析可以用受控 JSON 夹具或已发现的最小进程 seam 隔离；不得为了测试创建一个项目级通用命令框架。

## Decisions

### 1. 一个受限请求/结果 seam 隐藏 ripgrep 细节

请求只允许已验证的 workspace 标识/根、一个非空精确文本、受控文件范围、上下文行数和有限的结果预算。结果包含排序后的文本命中、是否截断和结构化诊断；调用者不会看到 `rg` flags、进程对象或 JSON 行协议。

初期只支持固定字符串查询，不支持调用方传入正则或命令参数。这样精准定位标识符、配置键、错误文本和已知片段已足够，同时避免不可控的正则复杂度和隐式语义。需要正则搜索时应单独设计请求语义与资源预算。

### 2. 根目录和路径必须保持在已授权工作区

Adapter 必须从已确认的 workspace 根取得实际工作目录，解析并验证其规范路径。调用方提供的路径过滤只能在该根内生效；返回路径一律规范化为相对工作区路径，不能返回或访问根外文件。搜索遵循经盘点确认的忽略/隐藏文件政策，且不能因用户输入关闭该政策。

这比把任意 path 和 glob 传给进程更安全，也让后续证据能以稳定 workspace-relative `SourceRef` 表达。

### 3. 使用参数化、可取消且有预算的进程调用

通过 `ProcessBuilder` 或已存在的等效本地进程 seam，以参数列表调用 `rg`；绝不调用 shell，绝不把查询、路径或过滤器拼到命令字符串。Adapter 使用可机器解析的 JSON 输出，并设置明确的超时、最大输出字节数、最大命中数和上下文限制。超时或超量时必须终止/清理子进程并返回可识别的截断或失败诊断。

`rg` 的“无匹配”退出语义必须映射为成功的空结果；不可执行、退出码异常、JSON 解析错误和编码错误必须保留为失败诊断，不能伪装成空结果。

### 4. 结果先规范化再确定性排序，并只产生文本证据

每条命中必须包含规范相对路径、开始/结束行列、受限摘录和匹配文本/范围，随后按路径、行、列和稳定的同位次序排序。结果预算和截断标识在该规范化语义下应用，以减少底层进程并行输出造成的不确定性。

命中生成的 `SourceRef`/Evidence 必须标明“文本搜索”或 core 中对应的受确认来源类别、位置和置信度。Adapter 不创建 `IMPLEMENTS`、`CALLS`、`REFERENCES` 或其他关系；这种关联只能在后续具有额外证据的能力中产生。

### 5. 不在此阶段持久化或替换当前检索

Adapter 返回内存候选，既不写入 `knowledge_object`/`knowledge_relation`，也不修改 `LuceneSearchIndex` 或现有 `SearchHit`。未来混合检索将通过它的独立 seam 消费候选并进行去重、版本优先和证据回传；本 change 的回滚只需移除对 Adapter 的调用。

### 6. `rg` 发现、忽略和资源策略在 Adapter 边界固定

Adapter 将只接受构造时提供的可执行文件引用；默认值为字面量 `rg`，由 `ProcessBuilder` 在实际搜索时按操作系统规则解析。它不执行 shell、`where`、`which`、PATH 扫描或调用方提供的发现命令，也不在每次搜索前运行版本探测。当前受控开发环境记录的基线为 `ripgrep 12.1.1 (rev 7cb211378a)`；实现依赖其稳定的 JSON 与固定字符串能力，而不是解析版本号，因此任何不能启动或不能产生约定 JSON 的工具分别返回 `TOOL_UNAVAILABLE` 或 `MALFORMED_OUTPUT` 诊断，绝不伪装为成功空结果。

调用不传递 `--hidden`、`--no-ignore` 或任何忽略策略覆盖项：搜索遵循 ripgrep 默认的 ignore/hidden-file 政策，受限路径范围只能缩小候选集合，不能放宽该政策。固定初始预算为：查询最多 512 个字符、最多 64 个受限路径、上下文最多 3 行、可观察结果最多 200 条、单条摘录最多 4,096 个字符、进程标准输出最多 1,048,576 字节、超时 5 秒。调用请求可以请求更小的上下文或结果数，但不能提高或替代这些不可变配置上限。超限、超时或读取截断都会清理进程并产生可见的不完整/失败诊断。

### 7. 宽容请求、封闭工作区目录与证据候选是唯一公开边界

`RipgrepCodeSearchAdapter` 是唯一公开执行 seam，其构造器接收不可变的 `workspaceId -> Path` 注册映射并在构造时规范化、解析真实根目录。它不包装 HTTP 的包私有 `WorkspaceRegistry`，也不接受调用方传入的绝对根目录。`CodeSearchRequest` 是宽容的调用 DTO：空查询、未知 workspace、越界/不存在 scope 和预算超限均在 `search` 中转为结构化诊断，且在启动进程前返回。

每个 `CodeSearchMatch` 只包含规范相对路径、位置范围、受限摘录、匹配文本与 `Evidence(SOURCE_TEXT)`；该 Evidence 内的 `SourceRef` 使用 `engineering-workspace` 与 `workspaceId:relativePath` 定位。它是内存中的文本观察，不构造 `Relation`，也不把 `KnowledgeObject`、SQLite 或 Lucene 暴露为该 Adapter 的依赖。

## Risks / Trade-offs

- [查询或路径导致命令注入/越界访问] → 不经 shell，使用固定参数列表，验证根与相对路径，拒绝未经授权的 flags。
- [巨大仓库或宽泛查询耗尽资源] → 固定字符串模式、结果/输出/上下文/超时预算和可见截断。
- [rg 无命中与工具故障混淆] → 单独映射无命中退出、不可用、异常退出和解析错误。
- [底层输出顺序不稳定] → 统一规范化和应用层排序后再返回。
- [文本命中被误用为关系事实] → 只返回来源证据，规范和测试禁止直接创建关系。
- [测试依赖本机安装或平台 shell] → 优先使用 JSON 输出夹具或现有最小进程 seam，测试 Adapter 的外部行为而非本机环境。

## Migration Plan

1. 核验前置 Gate 和当前 workspace/进程配置证据，确定 `rg` 发现策略与缺失时诊断。
2. 实现受限请求/结果值类型、进程调用、JSON 解析、路径验证、排序和错误映射；不注册到现有入口。
3. 使用受控夹具覆盖正常命中、无命中、异常、超时/截断、越界路径和不合法输出。
4. 更新 TODO 与 Verification，等待 ctags/Git Adapter 和混合检索再消费结果。

回滚时停止调用或移除该独立 Adapter；它没有数据库写入、索引写入或既有公开契约变更。

## Open Questions

无。具体 `rg` 可执行文件发现位置与当前 workspace 机制由前置盘点决定；若实际部署无法满足本设计的受限调用与失败诊断语义，必须更新本 design/spec，而不是回退到 shell 拼接。
