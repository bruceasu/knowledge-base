# Monorepo

## Scope
- Work inside the smallest relevant subtree.
- Do not assume one language rule applies to the whole repository.

## Routing
- Prefer the nearest local `AGENTS.md` and `CODEX.md`.
- Use static project-local review by default.
- When executable validation is authorized, use one project-local command
  before considering repository-root validation.

## Cross-Project Changes
- Map the affected projects and shared contracts first.
- Plan the work in phases.
- Validate after each project or phase, not only at the end.

## Shared Rules
- Keep shared prompt rules in `prompts/`.
- Keep project-specific rules near the project, not at the root.
