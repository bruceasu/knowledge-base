# AIW 与 OpenSpec 工作管理

## 所有权

- AIW 负责 Task 生命周期状态、分支、worktree、Session、handoff lineage 和外部映射。
- OpenSpec 负责 proposal、design、capability spec 以及 `tasks.md` 中的详细实现清单。
- GitHub 和 GitLab 仅在用户明确要求时作为可选投影使用。

不要创建第二套任务跟踪系统，也不要让 OpenSpec 的生命周期状态覆盖已解析的 AIW Task。

## Task 与变更映射

共享同一目标、分支、worktree、交付和归档生命周期的工作，使用一个 AIW Task 和一个 OpenSpec change。

普通实现切片作为 `tasks.md` 的清单项；只有当切片需要独立 worktree、交付或归档生命周期，并且用户批准拆分时，才创建独立的 AIW Task/change。

## Worktree 规则

- 通过 `aiw wt` 创建或解析实现 worktree。
- 默认使用一个 Task、一个 `feature/<task-id>` 分支和一个 `.wt/<task-id>` worktree。
- AIW 可用时不要直接使用原始 `git worktree`。
- 不要在与 Task 不匹配的工作区中静默实现。

## OpenSpec 规则

- 托管变更必须通过 `aiw new <task-id> --backend auto` 创建。
- 不要直接使用 `openspec new change` 创建托管变更。
- 如果 OpenSpec CLI 不可用，可以继续使用已有本地制品，但应报告未执行的 OpenSpec 操作。
- 同步时停止并报告冲突，不覆盖 AIW 或 OpenSpec 任一侧内容。

## 测试与完成

- 运行测试、构建、类型检查、格式化、lint 或验证脚本，需要用户明确授权。
- 完成实现后更新 `tasks.md`、TODO、Verification 和剩余 `%%` 说明。
- 报告静态证据以及未运行的检查。

# AIW Work Management Contract

Use this contract for engineering Skills in an AIW/OpenSpec repository.

## Ownership

- AIW owns Task lifecycle status, branch, worktree, Session, handoff lineage,
  and external mappings.
- OpenSpec owns proposal, design, capability specs, and the detailed
  implementation checklist in `tasks.md`.
- Workflow Core owns Work Items, Attempts, Gates, Evidence, write leases, and
  derived execution/validation/readiness state in `.ai/<task-id>/`.
- GitHub and GitLab are optional projections used only on explicit request.

Do not create a second task tracker or let OpenSpec lifecycle state override the
resolved AIW Task.

Do not treat `task.toml.status` as an independently writable execution source.
Request a Workflow Core transition through the managed Task context and report
its resulting summary. OpenSpec checklist prose is human-owned; generated
Workflow summaries and markers are Core-owned protected regions.

## Discover Commands

Before an unfamiliar or mutating operation, inspect `aiw help --json` or the
specific command help once. Use the installed command surface rather than
inventing subcommands.

Current AIW task lifecycle commonly uses `aiw new`, `aiw show`, `aiw status`,
`aiw done`, and `aiw archive`. Worktrees use `aiw wt`.

Do not install AIW or OpenSpec automatically.

## Resolve Or Create The Task

Resolve context in this order:

1. Task ID explicitly supplied by the user.
2. Task established by the active AIW Session.
3. AIW Task associated with the current worktree or branch.
4. A unique matching OpenSpec change.

If planning work needs a new lifecycle, create it through AIW. `aiw new
<task-id> --backend auto` may delegate artifact creation to an installed
OpenSpec CLI and otherwise uses AIW's native backend.

If several Tasks match, stop and ask for the Task ID.

## Task And Change Mapping

Use one AIW Task and one OpenSpec change for work sharing the same goal and
archive lifecycle. A Task does not imply a dedicated branch or worktree.

Keep ordinary implementation slices as checklist items in `tasks.md`. Create a
separate AIW Task/change only when a slice needs an independent worktree,
delivery, or archive lifecycle, and only after the user approves the split.

## OpenSpec Change Creation

For a managed AIW change, AIW is the required entry point even when OpenSpec is
installed:

```text
aiw new <task-id> --backend auto
```

This creates the AIW lifecycle record and delegates proposal/spec artifact
creation to OpenSpec when available. The resulting runtime record MUST be stored
at `.ai/<task-id>/task.toml` with the Task ID, status, branch,
worktree, `parent_branch`, and Session mapping.

Do not use `openspec new change <task-id>` directly for a managed change. That
command creates OpenSpec artifacts but does not establish AIW lifecycle
metadata. If a third-party or upgradeable OpenSpec skill instructs direct
creation, this contract takes precedence; use AIW first and then continue with
the OpenSpec artifact steps.

An OpenSpec-only change is allowed only when the user explicitly accepts that
it is not tracked by AIW. It must not be handed to the AIW implementation or
completion workflow until the Task and `task.toml` have been reconciled.

## Workspace Rules

- Work in the primary Git checkout and current branch for ordinary manual work.
  Automated Task execution uses an isolated worktree by default; use the
  documented explicit primary-workspace opt-out only when authorized.
- A Task lifecycle does not imply a feature branch or linked worktree.
- Use isolation only for parallel writes, conflicting work, long-running work,
  disposable experiments, or an explicit user request. State the reason before
  creating it.
- Create or resolve isolated worktrees only through `aiw wt`; never create one
  silently.
- Require explicit Task or Session context when several Tasks share the primary
  workspace. Never select the most recent Task by guesswork.
- Treat `unassigned` and unknown workspace bindings as read-only until the user
  explicitly binds or repairs them.p
- Specification artifacts created on the current branch must be committed
  before creating an isolated Task worktree. The new branch/worktree must inherit
  `task.toml`, proposal, design, specs, and `tasks.md` from that commit; do not
  copy them manually into the worktree.
- Record the source branch as `parent_branch` in the Task metadata before
  creating the Task branch/worktree. Completion merges only into that recorded
  parent branch; never infer the target from the current checkout.
- Do not use raw `git worktree` when AIW is available.
- Do not silently implement in a workspace that does not match the Task.
- Do not commit, merge, push, remove a worktree, or delete a branch merely
  because Task implementation is complete. Git delivery is separately
  authorized. Preserve Task resources on any failure or conflict.

If AIW is unavailable, report the missing capability and ask before using a raw
Git fallback.

## OpenSpec Operations

Use OpenSpec CLI for artifact apply, spec sync, or archive support when it is
already installed. Prefer AIW lifecycle commands that delegate with
`--backend auto` where supported.

If OpenSpec CLI is unavailable:

- continue from local artifacts when the requested operation can be completed
  safely;
- do not install it;
- report which OpenSpec operation was not executed.

For synchronization:

- OpenSpec may update AIW title, goal, and progress summary.
- AIW may update OpenSpec lifecycle status and Task/worktree references.
- Proposal, design, specs, and checklist content remain OpenSpec-owned.
- Stop on conflicts instead of overwriting either side.

Archive a primary Task after its checklist and Verification are complete; its
Git delivery remains unmanaged. Archive an isolated Task only after its branch
has been verifiably merged and its managed worktree and temporary branch have
been cleaned up. A cancelled discarded Task may archive with its reason.

## Sub-Agents


- Use them only for bounded static analysis, code location, or independent
  implementation fragments.
- Sub-agents must not run tests, final-artifact builds, network calls,
  permission escalation, commits, archive operations, or worktree operations.
- The main agent owns integration, lifecycle mutations, and the final report.
- Do not invoke `code-review` automatically after implementation.

## Tests

Writing or editing tests is allowed. After implementation, compile the changed
project: first use `compile*` under `scripts/` or at the repository root, and
otherwise use the narrowest language-level compiler command. The compile path
must not retain a final distributable artifact, and `build*` scripts are not
compile scripts. Running tests, final-artifact builds, type checks, formatters,
linters, vet, or verification scripts requires an explicit user instruction.

After development is complete, ask once whether the user wants one focused test
command run. Include the exact command, scope, and expected duration. Default to
not testing when the user declines or does not respond.

Broader test or build scope requires separate approval.

## Completion

After implementation:

- update the selected `tasks.md` item, TODO, Verification, and remaining `%%`
  notes;
- synchronize the coarse AIW Task status without overwriting OpenSpec content;
- report static evidence and checks not run;
- report Task completion separately from Git delivery. Do not automatically
  commit, merge, push, clean a worktree, delete a branch, or archive. When Git
  delivery is explicitly requested, validate the recorded parent branch and
  preserve resources on partial or failed work.

# AIW Work Management Contract

Use this contract for engineering Skills in an AIW/OpenSpec repository.

## Ownership

- AIW owns Task lifecycle status, branch, worktree, Session, handoff lineage,
  and external mappings.
- OpenSpec owns proposal, design, capability specs, and the detailed
  implementation checklist in `tasks.md`.
- Workflow Core owns Work Items, Attempts, Gates, Evidence, write leases, and
  derived execution/validation/readiness state in `.ai/tasks/<task-id>/`.
- GitHub and GitLab are optional projections used only on explicit request.

Do not create a second task tracker or let OpenSpec lifecycle state override the
resolved AIW Task.

Do not treat `task.toml.status` as an independently writable execution source.
Request a Workflow Core transition through the managed Task context and report
its resulting summary. OpenSpec checklist prose is human-owned; generated
Workflow summaries and markers are Core-owned protected regions.

## Discover Commands

Before an unfamiliar or mutating operation, inspect `aiw help --json` or the
specific command help once. Use the installed command surface rather than
inventing subcommands.

Current AIW task lifecycle commonly uses `aiw new`, `aiw show`, `aiw status`,
`aiw done`, and `aiw archive`. Worktrees use `aiw wt`.

Do not install AIW or OpenSpec automatically.

## Legacy Task Path Adjustment

The canonical active Task directory is `.ai/tasks/<task-id>/`; archives remain at `.ai/archive/<date>-<task-id>/`. For a confirmed one-time relocation of the sole active legacy Task, stop the old AIW program first, stop that Task's Supervisor next, then verify there is no active Attempt, write lease, or other writer. Preserve the target `migrated-to` marker outside the target, require the legacy source to be the sole Task record, and stop on any destination data or identity conflict. Move the complete runtime directory, verify Task identity plus state, event history, reports, and artifacts, then use the new version for a read-only status check. Never move archived Tasks or infer a general migration workflow from this exception.

## Resolve Or Create The Task

Resolve context in this order:

1. Task ID explicitly supplied by the user.
2. Task established by the active AIW Session.
3. AIW Task associated with the current worktree or branch.
4. A unique matching OpenSpec change.

If planning work needs a new lifecycle, create it through AIW. `aiw new
<task-id> --backend auto` may delegate artifact creation to an installed
OpenSpec CLI and otherwise uses AIW's native backend.

If several Tasks match, stop and ask for the Task ID.

## Task And Change Mapping

Use one AIW Task and one OpenSpec change for work sharing the same goal and
archive lifecycle. A Task does not imply a dedicated branch or worktree.

Keep ordinary implementation slices as checklist items in `tasks.md`. Create a
separate AIW Task/change only when a slice needs an independent worktree,
delivery, or archive lifecycle, and only after the user approves the split.

## OpenSpec Change Creation

For a managed AIW change, AIW is the required entry point even when OpenSpec is
installed:

```text
aiw new <task-id> --backend auto
```

This creates the AIW lifecycle record and delegates proposal/spec artifact
creation to OpenSpec when available. The resulting runtime record MUST be stored
at `.ai/tasks/<task-id>/task.toml` with the Task ID, status, branch,
worktree, `parent_branch`, and Session mapping.

Do not use `openspec new change <task-id>` directly for a managed change. That
command creates OpenSpec artifacts but does not establish AIW lifecycle
metadata. If a third-party or upgradeable OpenSpec skill instructs direct
creation, this contract takes precedence; use AIW first and then continue with
the OpenSpec artifact steps.

An OpenSpec-only change is allowed only when the user explicitly accepts that
it is not tracked by AIW. It must not be handed to the AIW implementation or
completion workflow until the Task and `task.toml` have been reconciled.

## Workspace Rules

- Work in the primary Git checkout and current branch for ordinary manual work.
  Automated Task execution uses an isolated worktree by default; use the
  documented explicit primary-workspace opt-out only when authorized.
- A Task lifecycle does not imply a feature branch or linked worktree.
- Use isolation only for parallel writes, conflicting work, long-running work,
  disposable experiments, or an explicit user request. State the reason before
  creating it.
- Create or resolve isolated worktrees only through `aiw wt`; never create one
  silently.
- Require explicit Task or Session context when several Tasks share the primary
  workspace. Never select the most recent Task by guesswork.
- Treat `unassigned` and unknown workspace bindings as read-only until the user
  explicitly binds or repairs them.p
- Specification artifacts created on the current branch must be committed
  before creating an isolated Task worktree. The new branch/worktree must inherit
  `task.toml`, proposal, design, specs, and `tasks.md` from that commit; do not
  copy them manually into the worktree.
- Record the source branch as `parent_branch` in the Task metadata before
  creating the Task branch/worktree. Completion merges only into that recorded
  parent branch; never infer the target from the current checkout.
- Do not use raw `git worktree` when AIW is available.
- Do not silently implement in a workspace that does not match the Task.
- Do not commit, merge, push, remove a worktree, or delete a branch merely
  because Task implementation is complete. Git delivery is separately
  authorized. Preserve Task resources on any failure or conflict.

If AIW is unavailable, report the missing capability and ask before using a raw
Git fallback.

## OpenSpec Operations

Use OpenSpec CLI for artifact apply, spec sync, or archive support when it is
already installed. Prefer AIW lifecycle commands that delegate with
`--backend auto` where supported.

If OpenSpec CLI is unavailable:

- continue from local artifacts when the requested operation can be completed
  safely;
- do not install it;
- report which OpenSpec operation was not executed.

For synchronization:

- OpenSpec may update AIW title, goal, and progress summary.
- AIW may update OpenSpec lifecycle status and Task/worktree references.
- Proposal, design, specs, and checklist content remain OpenSpec-owned.
- Stop on conflicts instead of overwriting either side.

Archive a primary Task after its checklist and Verification are complete; its
Git delivery remains unmanaged. Archive an isolated Task only after its branch
has been verifiably merged and its managed worktree and temporary branch have
been cleaned up. A cancelled discarded Task may archive with its reason.

## Sub-Agents


- Use them only for bounded static analysis, code location, or independent
  implementation fragments.
- Sub-agents must not run tests, final-artifact builds, network calls,
  permission escalation, commits, archive operations, or worktree operations.
- The main agent owns integration, lifecycle mutations, and the final report.
- Do not invoke `code-review` automatically after implementation.

## Tests

Writing or editing tests is allowed. After implementation, compile the changed
project: first use `compile*` under `scripts/` or at the repository root, and
otherwise use the narrowest language-level compiler command. The compile path
must not retain a final distributable artifact, and `build*` scripts are not
compile scripts. Running tests, final-artifact builds, type checks, formatters,
linters, vet, or verification scripts requires an explicit user instruction.

After development, report which tests were not run. When a focused test
would add useful evidence, give one optional command with scope and expected
duration; do not ask solely because implementation finished. Run tests only
under the repository's runtime authorization rules. Broader tests or builds
require separate approval.

## Completion

After implementation:

- update the selected `tasks.md` item, TODO, Verification, and remaining `%%`
  notes;
- synchronize the coarse AIW Task status without overwriting OpenSpec content;
- report static evidence and checks not run;
- report Task completion separately from Git delivery. Do not automatically
  commit, merge, push, clean a worktree, delete a branch, or archive. When Git
  delivery is explicitly requested, validate the recorded parent branch and
  preserve resources on partial or failed work.
