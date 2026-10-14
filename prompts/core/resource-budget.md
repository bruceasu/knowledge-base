# Resource Budget

## Default Budget

For an ordinary implementation request:

- tests and builds: `0`
- formatter, linter, type-checker, vet, and verification runs: `0`
- network calls and dependency downloads: `0`
- permission probes and privilege escalation requests: `0`
- `codex-auto-review`, sub-agents, and repeated review passes: `0`
- post-edit command validation: at most `1` static/read-only command

Implementation does not imply authorization for runtime validation.

## Discovery Budget

- Use at most three targeted discovery batches before the first edit unless a
  concrete blocker remains.
- Batch related reads and searches.
- Read relevant symbols or excerpts instead of entire large files.
- Do not dump full logs, generated output, vendor trees, or lockfiles.
- Stop searching when enough evidence exists to make the scoped change.

## Retry Budget

- Do not repeat the same or an equivalent failed command.
- Allow one cheap corrected retry only for a command spelling, shell entrypoint,
  or path mistake.
- After a permission failure, stop that path. Do not try alternate shells,
  escalation, or broader commands unless the user authorizes the required
  action.

## Network And Permissions

- Keep network access off by default.
- Do not probe permissions with a command expected to fail.
- Ask for permission only when it is essential to the requested outcome.
- Explain the exact action, scope, expected cost, and risk before asking.

## Reporting

State what was changed, what static evidence was reviewed, what commands ran,
what was intentionally skipped, and what optional focused check the user may
authorize.
