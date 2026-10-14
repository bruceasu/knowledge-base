## Context

Git object IDs and tree changes are immutable local evidence, unlike a commit subject or an LLM interpretation. The platform needs that evidence to explain why a code or document candidate is recent or changed, but it must neither expose arbitrary repository commands nor treat natural-language commit metadata as proof of a requirement/design relationship.

This Adapter follows the same local, workspace-confined process boundary as the ripgrep and Ctags tasks. It consumes a local repository only and returns `GitEvidenceResult` in memory. It does not fetch remotes, mutate a working tree, create graph relations, or write SQLite/Lucene; later hybrid retrieval and Context Builder decide how to rank or display this evidence.

## Goals / Non-Goals

**Goals:**

- Provide one bounded read-only `inspect(GitEvidenceRequest) -> GitEvidenceResult` seam for resolved local commit selections and in-workspace file changes.
- Return full commit object IDs, parent IDs, audited timestamps/limited metadata, change kind, normalized old/new relative paths, and Git source evidence without human-output parsing.
- Enforce that the target Git repository contains the registered workspace, that requested commits are real commit objects, and that all returned files remain in the workspace subtree.
- Give deterministic results and diagnostics for non-Git workspaces, unavailable Git, invalid/missing commits, shallow history, rename/copy/delete, binary files, timeouts and bounded ranges.

**Non-Goals:**

- Do not fetch, clone, checkout, reset, rebase, merge, commit, push, alter remotes/branches, or otherwise mutate Git state.
- Do not accept arbitrary revision expressions or Git flags from callers; do not access a repository outside the registered workspace boundary.
- Do not parse commit messages/diffs to infer implementation, verification, ownership, requirement, design or code relations.
- Do not persist evidence, build a full blame history, calculate semantic diffs, replace existing synchronization, or change SQLite/Lucene/HTTP/CLI/MCP behavior.

## Design Readiness

**FD_APPLIED**

受管 Feature Design 已明确 Git 的领域边界：commit object ID 和 tree change 是可检验事实；subject/author 等仅为受控元数据；“该提交实现某需求”不是本 Adapter 能声明的结论。`GitEvidenceResult` 不等同于 `Relation`，不会绕过 foundation 的少量关系词汇。

**执行前置 Gate：** foundation、SQLite Store、ripgrep 和 Ctags tasks 已完成；前置盘点已确认 workspace 根/注册、路径验证、进程配置和本地 Git 可用性策略。实施前还必须定义允许的 commit 选择上限、timeout 和受控 metadata 字段；若 workspace 与 Git root 的嵌套关系不符合下面的限制，必须更新设计后再实现。

最高测试 seam 是 `inspect` 的不可变提交/文件变更结果和诊断。测试使用临时本地 Git 仓库创建小历史；不得通过远程服务或真实用户仓库验证。

## Decisions

### 1. 请求使用已解析的 commit 选择，而非任意 rev 字符串

请求只包含已验证 workspace、显式完整/短 object ID 的有限选择或一对经验证的边界 object ID、受控相对路径范围及结果预算。Adapter 先解析并确认每个对象是 commit，再读取历史；调用方不能传入任意 Git revision expression、options、environment 或 shell 片段。

这将错误输入限制在可诊断的“未知/非 commit/超预算”情形，避免把 Git CLI 变成通用仓库查询接口。对范围的具体 inclusivity 必须由请求值类型显式表达，不能依赖 Git 命令字符串的隐式语义。

### 2. Git root 必须包含 workspace，结果保持 workspace-relative

Adapter 解析 Git top-level 后验证 registered workspace 位于该 repository 内；若 workspace 是 repository 的子目录，所有文件筛选和结果先转换到 repo-relative，再规范化回 workspace-relative。根外路径、submodule/嵌套仓库歧义和不能规范化的路径必须被拒绝或明确诊断。

因此 Git 证据可与文档、ripgrep 和 Ctags 的 `SourceRef` 对齐，而不会泄漏上级仓库或同一仓库的无关目录。

### 3. 使用机器可解析的只读 Git 输出

调用通过参数化进程 API、固定只读命令 profile 和无 pager 设置进行；绝不调用 shell。文件变化和路径使用 NUL 分隔或等效机器可解析格式，不能依赖人类可读的 `git log`/`diff` 文本。提交以全对象 ID、父 ID、可审计时间和受控、限长的可选 metadata 表示；文件变化以 ADD/MODIFY/DELETE/RENAME/COPY 等可确认类型和 old/new path 表示。

二进制文件只报告变更事实和路径，不返回内容或假设可解析文本。rename/copy 必须保留两端路径和 Git 给出的状态，不能将其悄悄降格为两个无关文件。

### 4. 确定性、预算化的历史语义

在请求确认的 commit 集合中，Adapter 按固定的拓扑优先、旧到新顺序返回提交；同一层的稳定 tie-breaker 使用完整 object ID。每个提交内的文件变化按规范化路径和状态稳定排序。提交数、文件变化数、输出字节和执行时间均受预算限制，截断后结果必须带 `incomplete` 和原因。

浅历史、缺失祖先或已清理对象不能被虚构为“没有变化”；结果必须说明历史不完整/对象不可达的证据限制。

### 5. Git 输出只产生证据，不自行形成工程关系

每条结果带 Git 来源、commit OID、可用路径和位置约定，但不直接写 `knowledge_relation`。即使 commit message 有“implements”字样，Adapter 也不会创建 `IMPLEMENTS`/`VERIFIED_BY` 等关系；需要该类关联时，后续能力必须提供额外、可审计证据和明确 relation vocabulary。

### 6. 保持本 change 的只读、可撤回边界

Adapter 不保存结果、不修改当前 source files、SQLite、Lucene、配置或公开路由。它的回滚是禁用/移除调用；没有数据迁移、远程副作用或 Git 工作树恢复步骤。

### 7. Fix the initial local-readonly profile, selection grammar, and budgets

The initial profile ID is `git-local-readonly-v1`. Its verified baseline is
`git version 2.45.1.windows.1` at the explicitly configured executable
`C:\Program Files\Git\cmd\git.exe`; the Adapter does not run `where`, `which`,
PATH discovery, remote fallback, or executable substitution. Compatible Git
must be version 2.30 or newer and support the fixed global arguments
`--no-pager --literal-pathspecs -c core.pager=cat -c color.ui=false`. Every
process also receives Adapter-owned `GIT_TERMINAL_PROMPT=0` and
`GIT_OPTIONAL_LOCKS=0`. Caller environment entries, Git flags, command names,
and configuration overrides are never accepted.

Callers choose commits through one of two immutable value types:

- `ExplicitCommits` contains 1 through the effective commit budget (64 by
  default, 256 hard maximum) complete lowercase hexadecimal OIDs.
  The required length is 40 for a `sha1` repository and 64 for `sha256`; short
  IDs, refs, tags, `HEAD`, ranges, and other revision expressions are invalid.
  After object validation, the selected induced parent graph is ordered
  topologically old-to-new, with the full OID as the deterministic tie-breaker.
- `InclusiveCommitRange(oldestInclusive, newestInclusive)` contains two complete
  OIDs. `oldestInclusive` must be an ancestor of `newestInclusive`; both
  endpoints are returned. The Adapter constructs the internal traversal as the
  validated oldest commit followed by `rev-list --ancestry-path --topo-order
  --reverse oldest..newest`. The constructed range argument is never caller
  text. Missing ancestry in a shallow repository is incomplete history, while
  a confirmed non-ancestor pair is an invalid selection.

The fixed read-only command allowlist is `--version`, `rev-parse` for top-level,
object format and shallow state, `cat-file -t` for exact commit-object
validation, `merge-base --is-ancestor`, `rev-list` for the bounded inclusive
range, `show -s --no-show-signature` for NUL-delimited metadata, and
`diff-tree` with `-r -z --name-status/--numstat -M -C` for path-level facts.
Non-root file changes are compared with the commit's first parent; a root commit
is compared with the empty tree. No content patch or semantic diff is returned.
The metadata record is limited to full OID, all parent OIDs, author and
committer names/emails, authored/committed ISO instants, and the single-line
subject. Names are capped at 256 UTF-8 bytes, emails at 320, and subject at
1,024; commit bodies, signatures, notes, and mailmaps are excluded.

The registered workspace is canonicalized first. `rev-parse --show-toplevel`
must resolve to the same directory or a canonical ancestor of the workspace;
nested repositories and a Git root below/outside the workspace are rejected.
Historical path filters cannot require filesystem existence, so they are
lexically normalized after rejecting absolute, empty, NUL-containing, dot, and
parent-segment paths, capped at 64, prefixed with the canonical workspace's
repo-relative path, and passed only after `--` under `--literal-pathspecs`.
Returned old/new endpoints are converted from repo-relative to normalized `/`
workspace-relative paths; records outside that subtree are ignored with an
explicit diagnostic and make the result incomplete.

Defaults are 64 commits, 2,000 total file changes, 4,194,304 accumulated stdout
bytes, and one 10-second total deadline. Hard configuration ceilings are 256
commits, 10,000 total changes, 16,777,216 stdout bytes, 64 path filters, and 60
seconds. Request value `0` selects the configured default for commit/change
budgets; negative or over-ceiling values fail before launch. Each subprocess
may consume only the remaining byte/deadline budget. Budget exhaustion returns
the bounded candidates with `incomplete=true` and a specific truncation
diagnostic; timeout/unavailable/incompatible/malformed outcomes never become a
successful empty history.

## Risks / Trade-offs

- [任意 rev/flag 变成通用 Git 执行入口] → 只接受验证后的 object ID/有限范围，使用固定参数 profile 和无 shell 调用。
- [workspace 位于仓库子目录导致路径泄漏] → 验证 Git root 包含 workspace，双向转换并仅返回 workspace-relative path。
- [rename、空格或非 ASCII 文件名被误解析] → 使用 NUL 分隔机器格式，测试 rename/Unicode/空格路径。
- [浅克隆和历史截断被误作完整历史] → 显式检测并返回 incomplete/diagnostic，不推断缺失提交。
- [commit message 被误用为事实] → 限定为 metadata，规范和测试禁止产生工程关系。
- [历史查询过大或 Git 不可用] → commit/file/byte/time budgets 和明确工具/超时/对象错误诊断。

## Migration Plan

1. 核验前置 Gate，记录本地 Git 发现、只读命令 profile、workspace/Git-root 规则、选择语义和资源预算。
2. 实现受限请求/结果、object/根验证、机器格式解析、提交/文件规范化、排序和诊断；不接入现有入口。
3. 使用临时本地仓库夹具覆盖线性/分支历史、rename/copy/delete、二进制路径、缺失 commit、浅历史标记和错误边界。
4. 更新 TODO 和 Verification，等待混合检索/Context Builder 以 evidence-only 方式消费结果。

回滚时只停止调用 Adapter；它从不执行写 Git 命令、网络访问、数据库写入或索引更新。

## Open Questions

Decision 7 resolves the initial commit-selection grammar, inclusive range
semantics, audit metadata, workspace mapping, executable policy, fixed command
profile, and resource ceilings. Any request for arbitrary revision expressions,
remote history, blame, or broader metadata requires a separate reviewed change.

无。commit selection 的具体值类型由实现前的前置 Gate 固定；若调用方确实需要任意 revision expression、remote history 或 blame，则必须创建单独的权限、资源和隐私设计，不能扩大本 Adapter 的请求字段。
