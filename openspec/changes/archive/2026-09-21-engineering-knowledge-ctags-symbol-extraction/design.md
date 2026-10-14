## Context

`engineering-knowledge-ripgrep-code-search` 提供精确文本证据，但其命中没有语言结构。Universal Ctags 能以 JSON 行输出源文件的语言、种类、名称、范围、scope 和签名等静态分析信息；这些信息足以产生可靠的“符号候选”，但不足以证明调用、覆盖、实现或数据流关系。

本 change 在 SQLite Store 和受限工作区/外部进程约定之后进行，初期面向本仓库主语言 Java。它只返回内存中的 `SymbolExtractionResult`，不改变现有 Java 编译、Maven、Lucene 或应用运行路径。未来的混合检索会消费已规范化的候选，而不是解析 ctags 原始 JSON。

## Goals / Non-Goals

**Goals:**

- 提供一个 `extract(SymbolExtractionRequest) -> SymbolExtractionResult` 深 Module，隐藏 ctags 命令、JSON 和字段差异。
- 从确认支持的 Java 文件中提取类、接口、方法、字段和可确认的其他符号，保留工作区相对来源位置、限定名称、签名、语言、种类、工具 profile 和静态分析证据。
- 对重载、同名、缺失范围、未知 kind、工具版本差异、无效 JSON、超时和工具不可用提供确定性结果或诊断。
- 使同一工作区快照和同一 ctags profile 的重跑产生可比较、稳定排序的候选集合。

**Non-Goals:**

- 不构建调用图、引用图、继承图、类型解析、控制/数据流图或 `IMPLEMENTS`/`CALLS` 等关系。
- 不支持所有语言或任意 ctags flags；不引入 Tree-sitter、LSP、Java 编译器分析、Python 或 LLM 推断。
- 不持久化符号、修改新旧 SQLite 数据、替换 ripgrep/Lucene、或接入 HTTP/CLI/MCP/现有搜索路由。
- 不创建通用外部工具框架；若没有已确认的最小进程 seam，只创建 Adapter 内部所需的窄边界。

## Design Readiness

**FD_APPLIED (profile verified 2026-09-20)**

已审视“符号”“文本命中”“关系”和“来源证据”的边界：`ExtractedSymbol` 是 ctags 静态分析候选；它含有定位证据，但不自动形成代码关系或 canonical 业务事实。没有仓库级 glossary/ADR 覆盖这些新术语，故它们以本 change 的设计为准。

**执行前置 Gate：** foundation、SQLite Store 和 ripgrep Adapter 已完成；前置盘点已确认工作区根、路径验证、进程调用和配置惯例。2026-09-20 已用明确路径的 `C:\green\universal-ctags\ctags.exe` 验证 Universal Ctags 6.2.0（`p6.2.20260920.0`）：具备 `+json` feature、Java parser 以及所需的 JSON 字段。旧的 `C:\green\ctags.exe`（Exuberant Ctags 5.8）仍不符合本 change，实施不得回退使用它。若后续配置的版本/profile 无法满足以下身份和诊断规则，必须先更新本 change。

最高测试 seam 是公开 `extract` 操作的规范化符号、排序和诊断；使用版本化 JSON 行夹具验证解析。测试不会通过真实 Java 编译器、LSP、数据库或搜索服务来间接验证 ctags。

## Decisions

### 1. 只接受受限工作区和确认支持的 Java 输入

请求只包含已验证 workspace、受控的相对文件范围和预算，不能携带原始 ctags 命令或 flags。初版只提取仓库盘点确认的 Java 源文件种类；不认识的语言或根外路径必须产生明确的跳过/无效请求诊断，而不是交给工具任意扫描。

这使符号结果的 `SourceRef` 稳定地以 workspace-relative path 表示，也避免把 ctags 变成任意文件读取入口。多语言支持应在各语言的语义和测试确定后独立扩展。

### 2. 固定 Universal Ctags JSON profile 并显式处理字段缺失

Adapter 使用参数化进程调用请求 JSON 输出和实施此规范所需的确认字段，过滤 metadata/progress 记录，仅解析 tag 记录。它保存或返回所用工具版本/profile，以便解释不同机器的差异。未知 kind、未知字段、缺少 path/name 或无效 range 不能被静默转换为虚假符号；根据严重性，它们应被跳过并诊断，或使请求失败。

不采用 ctags 文本 tags 文件：JSON 能保留位置、语言、scope、signature 和类型字段，且更容易为夹具测试和向后兼容规范化。

### 3. 以可解释的复合键形成符号身份

每个候选的稳定符号身份由确认的 workspace/repository 身份、规范相对路径、语言、符号种类、限定名称和签名（可用时）构成。方法重载必须用签名或 ctags 提供的等效区分字段区分；若工具无法提供所需的限定名称/签名，Adapter 只能使用明确标记为位置敏感的回退 discriminator，并返回诊断，不能将两个不同符号合并。

行号/范围是定位证据，不作为首选身份来源，因为正文插入会改变行号。core 的 `SourceRef`/`Evidence` 约定将被复用，但本 change 不创建 `KnowledgeObject`，也不存在可复用的 public core symbol-ID encoder；候选标识因此遵守 Decision 8 的显式本地编码，绝不冒充 core object ID 或复制任一私有 SHA-256 实现。

### 4. 结果规范化为静态分析候选，不推断关系

`ExtractedSymbol` 需要包含 kind、language、simple/qualified name、signature（可用时）、source range、可选 scope、工具 profile 和 core-compatible `SourceRef`/Evidence。结果先去除完全等价重复记录，再按相对路径、起始位置、kind、限定名称和签名进行确定性排序。

每个候选仅说明“该工具在该输入位置提取到符号”。它不创建对象持久化、不写关系，也不因类名、接口名或关键词自动声明 `IMPLEMENTS`、`OVERRIDES`、`CALLS` 或 `REFERENCES`。

### 5. 有界、shell-free 的工具执行和明确失败语义

通过参数列表调用 Universal Ctags，绝不经 shell 或暴露用户 flags。执行遵守超时、文件数、输出字节和结果数预算，并在超量或超时后清理子进程。结果必须区分成功空集合、工具不可用、非预期退出、超时、解析/编码失败和受预算截断；不完整集合必须明确标识。

### 6. 先交付纯提取，持久化和检索另行组合

此 change 不直接向 `knowledge_object` 或 `knowledge_index_state` 写入，也不修改 Lucene。它的回滚是停止调用 Adapter。后续混合检索或明确的代码知识摄取 change 将决定何时以 Store 持久化候选及如何处理 source hash/staleness，避免在工具集成时同时引入 schema、调度和排序风险。

### 7. 固定并可探测的 Universal Ctags Java JSON profile

初版 profile 标识为 `universal-ctags-json-java-v1`。其已验证基线是 `C:\green\universal-ctags\ctags.exe` 的 Universal Ctags 6.2.0（`p6.2.20260920.0`，`+json`）；实现通过显式配置的可执行文件调用它，不执行 `where`、`which`、PATH 扫描或版本替代。部署可把可执行文件配置为 Linux 的 `ctags`，但必须由同一固定 profile 验证，不能因同名命令而假定兼容。

一次有效提取先用固定的 `--version` 记录实际工具标识，随后使用以下参数序列生成 JSON Lines：`--options=NONE --output-format=json --sort=no --fields=+n+K+S+e+l+p+z --languages=Java --kinds-Java=+acefgimpr -f - ./<validated-relative-java-file>...`。其中 `--options=NONE` 禁用用户/工作区 option files；本机 Windows 6.2.0 构建不接受 `--` 选项分隔符，故经 real-path 验证后的输入必须以 `./` 前缀传递，既不允许根外路径也不允许被解释为 option。进程工作目录始终是已验证 workspace 根，stderr 不作为 JSON 输入。

JSON tag 记录至少需要 `_type=tag`、`name`、`path`、`language=Java`、长名称 `kind` 与正数 `line`；profile 同时请求并在可用时保留 `end`、`signature`、`scope`、`scopeKind`。已确认支持 annotation、class、enum constant、field、enum、interface、method、package 与 record；local variable 保持关闭。没有 `signature` 的方法必须走已记录的位置敏感回退并带诊断，不能与重载静默合并。

默认预算为最多 64 个显式 Java 文件、200 个候选、1,048,576 bytes stdout 和 5 秒总超时；请求 `maxResults=0` 使用默认候选上限，负数或大于 200 的预算在启动前拒绝，配置超时不得大于 60 秒。进程无法启动时返回 `TOOL_UNAVAILABLE`；版本、JSON 或必需字段不兼容时返回 `INCOMPATIBLE_PROFILE`；超时、输出上限或结果上限必须带不完整诊断并清理子进程。

### 8. 候选标识是透明的 Adapter 本地键，不是 core object ID

`ExtractedSymbol` 的 `SymbolIdentity` 仅在本 Adapter 的 in-memory 候选集合中标识同一静态分析结果；它不是 `KnowledgeObject` ID、不会写入 Store，也不承诺未来持久化键。其值采用无 hash 的 `ctags-symbol-v1` 长度前缀 UTF-8 编码，顺序固定为 workspace ID、规范相对路径、`Java`、长名称 kind、限定名称和 discriminator，因此不会依赖私有的 document/SQLite 哈希细节。

正常 discriminator 使用签名（可用时；非方法可以为空）；若 method 缺少签名、或需要 scope 的成员缺少可形成限定名称的 scope，则以 `startLine` 和可用 `endLine` 组成 `location:` discriminator，并把 `locationSensitive=true` 与 `LOCATION_SENSITIVE_IDENTITY` 诊断一并返回。该回退明确可能随前置插行变化，绝不把裸行号伪装为主身份；未来将候选投影为 core object 时由后续 change 决定其映射。

## Risks / Trade-offs

- [Ctags 版本/字段差异导致错误解析] → 固定受支持 profile、记录版本、使用版本化 JSON 夹具并把缺失字段变为诊断。
- [重载或同名符号碰撞] → 优先 qualified name + signature；回退时显式位置敏感和诊断，绝不静默合并。
- [行号随编辑漂移] → 仅将行范围作为定位证据，不作为主身份。
- [ctags 误把复杂 Java 语法解析为符号] → 保留静态分析来源和工具 profile，不升级为工程关系或事实。
- [外部命令扫描超出预算] → 路径验证、语言限制、参数化调用和超时/输出/结果上限。
- [过早把提取和存储/检索耦合] → 本 change 只产出 in-memory 结果，持久化与路由由后续 change 明确负责。

## Migration Plan

1. 核验前置 Gate，记录 Universal Ctags profile、版本发现和 Java 支持证据。
2. 实现受限请求/结果、参数化执行、JSON 解析、符号规范化、身份/排序和诊断；不接入运行时入口。
3. 用小型 Java 和 JSON 夹具验证类、接口、字段、方法、重载、同名、缺字段、未知 kind 和失败语义。
4. 更新 TODO 和 Verification，等待 Git 证据及混合检索 change 消费该结果。

回滚时停止调用或移除 Adapter；因为本 change 不写数据库、索引或公开 API，现有代码功能保持不变。

## Open Questions

无。若实际 ctags profile 对 Java 不能提供身份所需字段，实施者必须在 implementation 前提出受控回退方案并更新 design/spec，不能用不稳定 title 或裸行号假装稳定符号 ID。
