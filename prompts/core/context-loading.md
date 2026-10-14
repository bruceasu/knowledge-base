# Context Loading

## Start Small
- Load root entry files, the nearest local entry files, and the files in `core/`.
- Add one repo-type prompt, one domain prompt, and one task-mode prompt by default.

## Expand By Signal
- Add more prompts only when the task clearly spans more than one context.
- Do not load both service and CLI prompts unless the task truly touches both.
- Read broad architecture docs only when local files are not enough.

## Reduce Bloat
- Avoid loading the whole prompt library.
- Avoid copying the same rule into many places.
- Drop prompts that stop being relevant as the task narrows.
