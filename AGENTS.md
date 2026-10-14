# AGENTS.md
This repository uses OpenSpec-lite workflow.
Before coding, ALWAYS read:
1. relevant files under:
   - openspec/changes/<task>/
   - openspec/specs/
2. especially:
   - task.md
   - design.md (if exists)
   - spec.md
Rules:
- Work on ONE task at a time.
- Keep changes scoped.
- Do not refactor unrelated modules.
- Preserve backward compatibility unless explicitly required.
- Prefer small reviewable diffs.
- Update TODO and Verification before finishing.
- Add risks/questions using `%%` notes instead of guessing.
- If stable requirements changed, update openspec/specs/.
- If architecture decisions changed, update design.md.
Git worktree convention:
- one task = one branch = one worktree
- branch: feature/<task-id>
- worktree: .wt/<task-id>

---

# AGENTS.md
Always respond in Chinese.
默认假设：
  - 主要是 Java + Maven / Gradle
  - 常见场景是 Spring Boot / 后端服务 / 企业应用
强调分层、接口稳定性、测试、数据库和事务边界
## Purpose
This Java repository is maintained with human oversight and AI coding agents.
Optimize for correctness, explicitness, backward compatibility, and reviewability.
---
## Core Working Rules
### 1. Plan first for non-trivial work
Before editing files, first provide:
1. Task understanding
2. Relevant modules / packages
3. Assumptions
4. Missing information
5. Proposed implementation plan
6. Risks
7. Validation steps
Do not jump directly into code changes for medium or large tasks.
### 2. Respect module and layer boundaries
Follow existing architecture and package conventions.
Typical boundaries:
- controller / handler layer
- service / application layer
- domain / business logic
- repository / persistence layer
- configuration / integration layer
Do not bypass service boundaries unless the current codebase explicitly does so and the change requires it.
### 3. Minimize blast radius
Prefer small, focused edits.
Do not perform broad refactors unless explicitly requested or required for correctness.
### 4. Preserve compatibility
Assume public APIs, request/response contracts, DTOs, events, and persistence behavior are compatibility-sensitive unless stated otherwise.
### 5. Make every change verifiable
Every meaningful change should include:
- relevant unit tests
- integration tests where needed
- build verification
- lint / style / static analysis where available
---
## Java-Specific Engineering Rules
### Layering
- Controllers should handle transport concerns, not business rules.
- Business logic should live in services or domain components.
- Repositories should focus on persistence, not orchestration.
- Avoid putting domain logic into entity classes unless the codebase already uses rich domain models intentionally.
### Transactions
- Be careful with transaction boundaries.
- Do not casually move transactional logic across layers.
- If changing transaction behavior, call it out explicitly.
### DTOs and Entities
- Do not expose persistence entities directly through API layers unless the project already follows that pattern.
- Preserve mapping conventions already used in the repository.
### Exceptions
- Follow existing exception handling conventions.
- Do not swallow exceptions silently.
- Preserve observable behavior for error codes and error payloads unless the task requires a change.
### Dependency Management
Treat dependency changes as high-risk.
Request approval before:
- adding new dependencies
- upgrading framework versions
- changing BOM / parent versions
- changing plugin configuration
---
## Testing Expectations
Prefer this order:
1. format / style checks if configured
2. compile
3. unit tests
4. integration tests
5. package / build
Add or update tests for:
- service logic
- edge cases
- error handling
- serialization / deserialization behavior
- persistence behavior when relevant
When touching Spring components, consider:
- controller tests
- service tests
- repository tests
- SpringBootTest or sliced tests only when justified
---
## High-Risk Changes
Pause and explain before:
- database schema or migration changes
- transaction boundary changes
- auth / permission changes
- changes to request/response schemas
- message/event contract changes
- dependency upgrades
- configuration or deployment changes
Explain:
- what changes
- why it is needed
- what could break
- how it will be validated
---
## Preferred Workflow
### Phase 1: Understand
Identify:
- main entry points
- affected package(s)
- related interfaces
- downstream effects
### Phase 2: Plan
Provide a concise plan with expected files/modules to change.
### Phase 3: Implement
Apply the smallest correct change.
Preserve coding style and nearby conventions.
### Phase 4: Verify
Prefer repository-standard scripts first.
If not available, use Maven or Gradle project conventions.
### Phase 5: Report
Summarize:
- files changed
- behavior changed
- assumptions
- risks
- validation run
- harness improvements suggested
---
## Validation
Prefer:
```bash
./scripts/verify.sh
```
If unavailable, use the appropriate project command set, for example:
Maven
```bash
mvn test
mvn verify
```
Gradle
```bash
./gradlew test
./gradlew build
```
Do not claim success without reporting what was actually run.

## Agent skills

- 工作管理与 AIW/OpenSpec 所有权：[docs/agents/work-management.md](docs/agents/work-management.md)
- 领域术语与架构决策：[docs/agents/domain.md](docs/agents/domain.md)
- Triage 标签：[docs/agents/triage-labels.md](docs/agents/triage-labels.md)

## Documentation Expectations
Update docs when changing:
  - API behavior
  - configuration
  - environment variables
  - build or developer workflow
  - migration requirements

## Communication Style
For non-trivial tasks, structure responses as:
  - Understanding
  - Relevant Packages / Modules
  - Assumptions
  - Plan
  - Implementation
  - Validation
  - Risks
  - Harness improvements suggested

---

<!-- aiw-prompts:java:agents begin -->
Always respond in Chinese.
Write prompts in Easy English when asked to draft prompts.

# AGENTS.md

## Purpose

This is the root rule file for mixed-language repositories.
Keep the active instruction set and execution budget small.

## Load Order

1. Apply this file.
2. Prefer the nearest local `AGENTS.md` or `CODEX.md`.
3. Load `prompts/core/resource-budget.md`,
   `prompts/core/universal-principles.md`,
   `prompts/core/validation.md`, and `prompts/core/communication.md`.
4. Add at most one repo-type prompt, one domain prompt, and one task-mode prompt.
5. Use `project-local > domain > language > repo-type > root` precedence.

Do not load the whole prompt library.

## Resource Guard

- Static analysis and editing are the default.
- Tests, builds, formatters, linters, type checks, verification scripts,
  network calls, permission probes, privilege escalation, `codex-auto-review`,
  and sub-agents have a default budget of zero.
- Implementation does not imply authorization to run them.
- Use no more than three targeted discovery batches before editing unless a
  concrete blocker remains.
- After editing, use at most one static/read-only validation command by default.
- Do not repeat equivalent commands.

Follow `prompts/core/resource-budget.md` and `prompts/core/validation.md` for
authorization and retry rules.

## Working Rules

- Plan first for non-trivial work.
- Inspect the nearest code, tests, config, and docs.
- Expand only when current evidence is insufficient.
- Change only what the task needs.
- Preserve current contracts unless explicitly changed.
- Keep diffs small, local, and reviewable.
- Update nearby docs when behavior or workflow changes.

## Stop And Ask

Pause when:

- local instructions conflict;
- external behavior is unclear;
- designs have materially different blast radius;
- migration, rollout, or compatibility policy is missing;
- runtime evidence is decisive but not already authorized.

## High-Risk Areas

Call out risk before changing dependencies, auth, permissions, billing, schema,
migrations, persistence, public APIs, events, CLI contracts, CI/CD, deployment,
concurrency, retries, timeouts, or shutdown behavior.

## Prompt Routing

- Mixed-language repository: `prompts/repo-types/monorepo.md`
- Python: `python/AGENTS.md` or `python/CODEX.md`
- Java: `java/AGENTS.md` or `java/CODEX.md`
- Go: `go/AGENTS.md` or `go/CODEX.md`
- Prompt changes: `prompts/domains/prompt-authoring.md`
- Task mode: one matching file under `prompts/task-modes/`

## Final Report

Include the change, reason, static evidence, commands actually run, checks not
run, residual risks, and optional focused checks that require authorization.

---

# AGENTS.md

## Purpose
This is the default local rule file for Java subtrees.
Use it when the task is mostly Java or the current directory contains Java markers.

## Inspect First
- `pom.xml`, `build.gradle*`, and wrapper files
- controllers or handlers near the touched path
- services, repositories, DTOs, and config classes
- tests near the touched code

## Detect The Java Domain
- Spring markers:
  `spring-boot`, `@RestController`, `application.yml`, `src/main/java`
  - also load `../prompts/domains/java-spring.md`

## Keep Stable
- controller, service, repository, and config boundaries
- request and response contracts
- transaction boundaries
- dependency injection and build-tool conventions

## High-Risk Java Areas
- dependency upgrades
- auth, schema, or migration changes
- transaction changes
- public API or event contract changes

## Validation Options
Use static review by default. Do not automatically run `scripts/verify.sh`,
Maven, Gradle, tests, or builds.

When the shared resource budget authorizes runtime validation, choose one
smallest relevant command:
- `mvn -pl module -am test`
- `./gradlew :module:test`

Ask before repository-wide commands. Rerun only after a relevant change.

## Escalation
If a deeper subtree has its own `AGENTS.md` or `CODEX.md`, prefer that local file.
<!-- aiw-prompts:java:agents end -->

## Agent skills

- Read `docs/agents/work-management.md` for lifecycle ownership.
- Read `docs/agents/domain.md` for project-domain conventions.
