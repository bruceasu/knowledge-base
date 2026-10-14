# Prompt Library

This directory stores small reusable prompt files for coding agents.

## Design Goals
- keep root files short
- keep expert rules small and focused
- avoid duplicated or conflicting rules
- support automatic routing first
- keep manual override easy

## Normal Load Set
For most tasks, load:
1. root entry files
2. nearest local `AGENTS.md` or `CODEX.md`
3. all files in `core/`
4. zero or one repo-type prompt
5. zero or one domain prompt
6. zero or one task-mode prompt

Add more only when the task truly spans more than one context.

## Priority
When rules overlap, prefer:
`project-local > domain > language > repo-type > root`

## Best Practices
- keep each file about one concern
- say when a file should be loaded
- say what must stay stable
- say how to validate
- remove stale rules when the codebase changes

## Manual Override
If auto-detection is wrong, explicitly name the prompt file you want to use.
