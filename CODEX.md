# CODEX.md
## Role
You are operating as a local coding agent for a Java codebase.
Follow AGENTS.md first, then apply these Java-specific execution preferences.
---
## Default Mode
For medium or high complexity work, start with planning mode.
Before editing files, provide:
- Understanding
- Relevant packages / classes
- Assumptions
- Missing information
- Step-by-step plan
- Risks
- Validation commands
Prefer `/plan` first for:
- new features
- refactors
- transaction changes
- API contract changes
- persistence changes
---
## Execution Preferences
### Preferred sequence
1. Inspect only relevant files
2. Identify affected package(s), classes, interfaces, and tests
3. Produce a plan
4. Apply minimal code changes
5. Add or update tests
6. Run validation
7. Summarize results and remaining risks
### Editing rules
- Keep changes localized
- Respect package structure
- Preserve existing naming and annotation patterns
- Do not rewrite large classes unless necessary
- Prefer consistency with nearby code over introducing a new style
---
## Java-Specific Guidance
### Spring / Dependency Injection
- Follow existing DI style: constructor injection, configuration style, bean patterns
- Do not introduce a new dependency injection pattern casually
### Persistence
- Preserve repository conventions
- Be explicit when changing query behavior
- Call out N+1, fetch behavior, pagination, or transaction implications when relevant
### API Contracts
- Treat DTO changes, JSON schema changes, endpoint behavior changes, and validation changes as externally visible
- Highlight these changes before proceeding
### Build Tools
Use repository-standard tooling first.
If no wrapper script exists, prefer:
- Maven: `mvn test`, `mvn verify`
- Gradle: `./gradlew test`, `./gradlew build`
---
## Approval Required Before
- adding or upgrading dependencies
- modifying schema or migrations
- changing auth/security logic
- changing public API contracts
- changing event/message formats
- changing CI/CD, Docker, or deployment config
- broad multi-package refactors
---
## Validation Preferences
Prefer:
```bash
./scripts/verify.sh
```
Otherwise use repository-appropriate commands.
Typical validation order:
    1. compile / check formatting if configured
    2. unit tests
    3. integration tests
    4. full build
Always report:
  - exact commands run
  - pass/fail results
  - what remains unverified

## Output Format
Use this structure for non-trivial tasks:
- Understanding
- Relevant Packages / Classes
- Assumptions
- Plan
- Implementation
- Validation
- Risks
- Harness improvements suggested

## Definition of Done
A task is not done until:
  - the requested behavior is implemented
  - relevant tests are updated or added
  - compatibility-sensitive changes are called out
  - validation is run or limitations are explicitly stated
  - docs are updated if required

## Forbidden Shortcuts
Do not:
  - skip tests silently
  - change API contracts without calling it out
  - add dependencies casually
  - change transaction behavior without explanation
  - perform broad cleanup unrelated to the task
  - claim build success without evidence

---

<!-- aiw-prompts:java:codex begin -->
# CODEX.md

Follow `AGENTS.md` first.

## Normal Java Prompt Set
- root `AGENTS.md` and `CODEX.md`
- `../prompts/core/*.md`
- `../prompts/repo-types/monorepo.md` only when the task spans more than one project
- `../prompts/domains/java-spring.md` when Spring markers exist
- one task-mode prompt

## Routing
- if the request is a bugfix, review, debugging, test, docs, feature, or risky change, also load the matching file in `../prompts/task-modes/`

## Execution
For non-trivial work, provide:
- Understanding
- Relevant Packages / Classes
- Assumptions
- Plan
- Validation
- Risks

Inspect contracts, tests, and configuration before broad exploration.
Follow the shared resource budget. Use static review by default; do not run
Maven, Gradle, tests, builds, or verification scripts automatically. When
authorized, run one module-focused command and ask before widening scope.
<!-- aiw-prompts:java:codex end -->
