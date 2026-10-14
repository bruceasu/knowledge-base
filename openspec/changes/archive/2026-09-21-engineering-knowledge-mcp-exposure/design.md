## Context

`KnowledgeRetriever`、Context Builder 与受控代码搜索已在 Java 应用层形成可测试的能力；前置审计同时确认，受控知识对象读取、固定范围且有证据的关系追踪，以及与 HTTP 生命周期解耦的工程 workspace catalog 尚无稳定应用层 Interface。MCP 的职责不是重做检索、读取、图扩展或预算，而是把这些能力映射为代理可调用、schema 稳定、工作区受限的本地工具。若 transport 直连 SQLite、Lucene、`rg`、ctags、Git、HTTP registry 或 Context Builder 内部类型，既会泄漏基础设施，也会绕过既有 provenance/freshness/error 规则。

此 change 的首个信任模型刻意很小：只接受由本机父进程启动的 stdio MCP transport，不监听 TCP/HTTP，不接受远程连接，也不新增写操作。进程的 OS 用户权限和已注册 workspace 是其唯一上下文；真正的认证、授权、租户隔离、远程 session 或网络部署必须在后续独立设计。

## Goals / Non-Goals

**Goals:**

- 提供一个 local-stdio MCP transport Adapter 和 `KnowledgeAgentFacade` Module，将六个稳定工具映射到应用层能力，而不让 transport 了解基础设施。
- 使 `knowledge_search`、`knowledge_get`、`code_symbol`、`code_search`、`trace`、`build_context` 的输入/输出 schema、workspace 规则、预算、错误类别、timeout/cancel 语义和 versioning 可测试且稳定。
- 只返回受限 workspace 内的数据：工作区相对路径、对象/来源 ID、provenance、freshness、evidence、incomplete/diagnostic；不输出绝对路径、SQL、命令行、栈追踪或原始基础设施错误。
- 所有操作只读、预算化并支持局部失败；工具不能触发摄取、索引重建、Git/文件写入、网络访问或现有路由的变化。

**Non-Goals:**

- 不建立 HTTP/TCP/SSE/remote MCP transport、认证、授权、token/session、租户隔离、公开服务发现或网络部署。
- 不新增或升级 MCP/JSON library；若现有依赖不能支持已确认的本地 stdio runtime，必须单独请求依赖批准，不得手写或隐式引入替代品。
- 不暴露 SQL、Lucene DSL、原始 `rg`/Ctags/Git 参数、任意文件路径、任意 revision 或任何写操作。
- 不修改 `KnowledgeBase`、`KnowledgeRepository`、Lucene、SQLite schema、HTTP/CLI、existing search/sync、部署配置或向量/LLM 能力。

## Design Readiness

**FD_APPLIED**

Runtime compatibility decision (2026-09-21): the real in-memory stdio harness confirmed that official Java SDK 2.0.1 does not register or propagate inbound `notifications/cancelled` to an executing server tool handler. The user approved a first-release contract that guarantees the ten-second hard deadline and cooperative thread interruption while treating protocol cancellation as best-effort under SDK 2.0.1. The async server plus bounded-elastic tool publisher interrupts timed-out work and preserves server availability. A hand-written cancellation/protocol interception layer remains forbidden; guaranteed protocol cancellation requires a later separately gated runtime compatibility change.

受管 Feature Design 已将 transport 信任模型固定为本地 stdio、只读、已注册 workspace。`KnowledgeAgentFacade` 是 transport 后的 Module：其 Interface 是受控领域请求/响应，而 MCP transport 是具体 Adapter；深度来自输入验证、输出脱敏、预算、错误规范和到应用层 Module 的正确映射，而非将六个底层工具接口原样转发给代理。

**执行前置 Gate：** Context Builder 与 hybrid retrieval 已完成；foundation 盘点已确认现有 MCP/transport/配置/生命周期集成点（如有）和 workspace registry。盘点发现的三个缺失应用层边界必须先在本 change 的前置 Work Items 中完成并验证；在此之前不得开始 facade、schema 或 transport 实现。随后还必须确认有已批准的 Java stdio MCP runtime 或得到显式依赖批准，并固定协议版本、tool schema version、最大请求/响应/timeout 和本地启动方式。未满足依赖 Gate 时，不得新增依赖、手写一个不兼容 server 或公开网络端口。

**当前 Gate 结论（2026-09-21）：** WorkItem 0.6 的源码、manifest 与离线完整 Maven 依赖树核验确认仓库原先没有 MCP runtime。用户随后明确批准采用稳定协议与当前主流方案，因此首版目标调整为 MCP `2025-11-25`，runtime 固定为官方 Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.1`。该稳定线支持 Java 17、提供 framework-free stdio transport，并与首版协议匹配；dependency Gate 已解除。`2026-07-28` 升级必须作为后续独立兼容性 change，不得在本 change 中使用 snapshot、手写协议或打开网络 transport。

最高测试 seam 是 tool schema 与 `KnowledgeAgentFacade` 的请求/响应及错误映射；transport 测试使用 in-process/stdin-stdout harness，确认 stdout 只承载协议消息。测试不需要实际 Codex、远程 client、网络监听、SQLite SQL 或外部命令。

## Decisions

### 0. 缺失应用层边界作为同一 change 的顺序化前置切片

前置审计是本 change 的 WorkItem 0.1；其结论不是放宽 Gate，而是将三个缺口显式加入同一 OpenSpec checklist。它们共享 MCP exposure 的交付和归档生命周期，不需要独立 branch、worktree 或 change，但必须在 facade/schema/transport 之前逐项完成：

1. 受控知识对象读取 Interface，以稳定对象 ID 和 workspace 上下文返回不可变领域结果及受控 `NOT_FOUND`/不可用结果，不暴露 `SQLException` 或 Store 类型。
2. 固定范围、只读且 evidence-bearing 的关系追踪 Interface，复用核心允许关系和证据规则，不暴露 Context Builder package-private 类型，也不提供任意图查询。
3. 应用层工程 workspace catalog，以不可变 workspace ID/root 描述和显式生命周期提供查询，不依赖 `me.asu.kb.http.WorkspaceRegistry` 或 `WorkspaceContext`。

这三个 Interface 只建立 MCP 所需的稳定读取边界，不改变 SQLite schema/write、HTTP registry 行为、现有 CLI/HTTP 生命周期或部署配置。每项必须有 focused tests 和静态兼容性证据；三项完成后才重新核验 MCP facade Gate。

### 1. local stdio 是唯一初始 transport

MCP server 仅通过 stdin/stdout 与启动它的本地父进程通信；日志写到受控 stderr/应用日志，不能污染协议 stdout。它不绑定任何网络地址，不加载 remote token，不访问 remote registry，也不尝试将本地 OS 权限伪装成应用级认证。

每次请求必须指定或继承一个已注册 workspace；未知、根外或未授权的 workspace 必须以不泄漏路径的错误处理。若未来需要共享服务、多用户、HTTP 或跨机访问，必须单独定义认证、授权、rate limit、audit 与秘密管理，不可复用本 change 的本地假设。

### 2. 用一个领域 facade 隐藏工具到应用层的映射

transport 不直接调用 Store、Lucene、ripgrep、Ctags 或 Git。它只调用 `KnowledgeAgentFacade` 的受控领域操作，后者复用现有应用层 Module：

| MCP tool | 应用层操作 | 受控结果 |
| --- | --- | --- |
| `knowledge_search` | `KnowledgeRetriever.retrieve` | 有 provenance/freshness/diagnostic 的检索项 |
| `knowledge_get` | 受控知识读取 | 单一当前对象及其来源/证据，或 `NOT_FOUND` |
| `code_symbol` | 对 `KnowledgeRetriever` 的符号过滤查询 | 当前可追溯符号候选 |
| `code_search` | 受控 ripgrep 代码搜索 | 文本证据候选及 truncation/diagnostic |
| `trace` | 受控 evidence-bearing relation read | 固定范围的关系/证据路径 |
| `build_context` | `ContextBuilder.build` | 预算化 `EngineeringContext` |

Facade 负责把每个 schema 映射到 workspace、预算和应用层请求，并统一脱敏/错误映射。它不创建“任意 tool registry”、不接受 tool-specific raw parameters，也不重写任何业务规则。

### 3. 工具 schema 是稳定公开契约，默认严格校验

每个工具有明确的 JSON input schema、output schema 和 schema version。输入只包含领域值：workspace ID、查询/对象 ID、受控 filters、结果预算、context intent/token budget、trace 起点与固定范围。未知字段、错误类型、过大预算、空值和越界 workspace 在进入 Facade 前被拒绝。

输出使用 stable IDs、workspace-relative locations、bounded excerpts/records、provenance/freshness/evidence 和 structured diagnostics。新增字段保持向后兼容；重命名、删除或改变语义必须通过新的 OpenSpec change/版本策略。工具错误统一映射为 `INVALID_ARGUMENT`、`NOT_FOUND`、`UNAVAILABLE`、`INCOMPLETE` 或 `INTERNAL` 等受控类别，绝不透传堆栈、SQL、绝对路径或命令文本。

### 4. 所有工具只读、可预算且可取消

Facade 在调用应用层前施加每工具的最大结果数、最大内容/响应大小和 deadline；调用方不能利用 `code_search`、`trace` 或 `build_context` 绕开原 Module 的预算。首版保证 deadline 触发当前调用的线程中断并保持 server 可用；SDK 2.0.1 的入站 protocol cancellation 仅为 best-effort，不保证传播，且不得通过手写协议层规避。

在 source 部分失败时，工具保留下游的 `incomplete`/diagnostics；在所有下游不可用时映射为 `UNAVAILABLE`。由于所有操作是 read-only，取消和失败不得改变 Store、索引、Git、文件或 legacy 服务状态。

### 5. `trace` 继续限定 evidence-bearing 的固定范围

`trace` 只允许从一个稳定对象 ID 出发，沿 core 允许且有 Evidence 的关系读取固定的一跳（或事先固定的等效预算），返回每一段的 relation type、对象/来源 ID、evidence 和 freshness。它不接受任意图查询、无界 depth、无证据边或由自然语言推断的关系。

这与 Context Builder 的关系规则一致，防止 MCP 对外暴露比内部语义更宽的图能力。

### 6. 不隐式改变依赖或运行时入口

实现优先使用已确认、已批准的 Java MCP runtime。如果没有可用 runtime，依赖 Gate 阻止实施，而不是把 dependency change 藏入任务。初期 transport 通过显式本地启动入口启用，且可独立关闭；现有 CLI/HTTP 不能自动启动或依赖它。回滚只需关闭该启动入口，因无写入/迁移而无需数据恢复。

首版交付中的 `KnowledgeMcpServerMain` 是独立且默认禁用的协议入口：它不由现有 CLI/HTTP 启动，未获得生产 `KnowledgeAgentFacade` 装配时只返回受控的 unavailable 结果。已验证的 `KnowledgeMcpServer`、六工具 handler 与 facade mapping 可由批准的本地父进程装配；生产 workspace catalog、reader、retriever、trace、code-search 与 Context Builder 的组合根、进程管理及发布配置不在本 change 内猜测或写入。启用真实本地工具服务必须由后续宿主集成提供这些依赖并复用同一只读边界，不能把占位入口描述为已完成的生产部署。

### 7. 应用边界 Gate 通过，并固定首版协议与资源策略

WorkItem 0.2–0.4 已补齐受控对象读取、固定一跳且有 Evidence 的关系追踪，以及独立于 HTTP 生命周期的不可变 workspace catalog。它们连同现有 `KnowledgeRetriever`、`RipgrepCodeSearchAdapter` 和 `ContextBuilder`，已形成 facade 所需的公开应用边界；transport 不需要直接引用 Store、Lucene、外部进程或 HTTP 类型。

首版 transport 目标协议固定为 MCP `2025-11-25`，工具 input/output schema version 独立固定为 `1.0`。协议由已批准的官方 Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.1` 实现；不使用 snapshot、Spring transport 或自写协议。启动入口固定为独立的 `me.asu.kb.mcp.KnowledgeMcpServerMain`，只由本地父进程显式启动，不增加现有 `KnowledgeBaseCli` 子命令、不修改 HTTP main，也不自动启动。stdout 只承载协议帧；受控运行日志只写 stderr，且不得记录请求正文、秘密、绝对路径、SQL、命令行或栈追踪。

每个工具请求必须显式携带 catalog 中已注册的 workspace ID，不允许隐式默认 workspace。未知 workspace 返回脱敏的 `NOT_FOUND`；workspace root 仅供应用层验证和相对化，绝不进入工具响应。首版固定限制如下：

| 边界 | 固定限制 |
| --- | --- |
| 单个协议请求 | UTF-8 编码后最多 65,536 bytes |
| 单个协议响应 | UTF-8 编码后最多 1,048,576 bytes；超限时返回受控错误，不输出部分 JSON 帧 |
| 单次工具调用 | 10 秒硬 deadline；下游现有 5 秒 retrieval/code-search deadline 保持不变 |
| `knowledge_search` / `code_symbol` | 查询最多 512 字符，最多 100 项 |
| `code_search` | literal 最多 512 字符、最多 64 个相对 scope、context 最多 3 行、最多 200 项 |
| `trace` | 固定一跳、最多 64 条 evidence-bearing relations |
| `build_context` | token budget 为 256–32,768，且仍受 1 MiB 响应上限约束 |

runtime 必须在 hard deadline 时中断当前调用或提供等效协作终止，并在 deadline 或 best-effort cancel 通知后保持 server 可用。SDK 2.0.1 不保证传播入站 `notifications/cancelled`；`CANCELLED` 仅在当前线程实际收到中断时返回。稳定错误类别固定为 `INVALID_ARGUMENT`、`NOT_FOUND`、`UNAVAILABLE`、`INCOMPLETE`、`DEADLINE_EXCEEDED`、`CANCELLED` 和 `INTERNAL`。`INCOMPLETE` 保留有效局部结果；`INTERNAL` 只返回通用安全说明。

## Risks / Trade-offs

- [local stdio 被误当成可安全远程服务] → 明确无网络 listener/remote session；远程化必须有独立 auth/permission design。
- [工具泄漏绝对路径、命令或内部错误] → Facade 统一输入验证、workspace-relative 输出和 error sanitization；transport 不直连基础设施。
- [MCP schema 漂移破坏代理] → 固定 tool/schema version，默认严格校验，breaking change 单独管理。
- [大请求耗尽本地资源] → 每工具 budget、response cap、deadline/cancel，复用下游限制。
- [MCP 绕过 evidence/freshness 规则] → 所有工具只调用应用层 Module，`trace` 固定有证据一跳。
- [缺少 runtime 时偷加依赖] → 依赖 Gate，要求明确批准，禁止隐式升级/手写替代。

## Migration Plan

1. 核验前置 Gate，确认 local stdio runtime、协议/schema version、启动方式、工作区 registry、请求/响应/超时上限和 stderr logging policy。
2. 实现 `KnowledgeAgentFacade`、严格 schema/错误映射和六个只读 tool handler；以 in-process harness 验证，无网络 listener。
3. 仅通过显式本地启动入口运行 transport；现有 HTTP/CLI/同步不改。
4. 用 fake/fixture 应用层 Module 验证 schema、路径脱敏、预算、取消、partial failure、trace evidence 和 stdout cleanliness。
5. 更新 TODO/Verification；若未来需要 remote MCP 或权限，创建单独安全/部署 change。

回滚时关闭/移除 stdio 启动入口；不会影响现有入口、SQLite、Lucene、Git 或工作区数据。

## Open Questions

无。MCP `2026-07-28` 协议/runtime 升级、生产 facade 宿主装配、remote transport、认证授权、多实例服务部署和任何遗留清理，均保留为分别受 Gate 控制、可独立回滚的后续 change；本 change 不预先创建或实现它们。
