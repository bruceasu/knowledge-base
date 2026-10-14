# Validation

## Default

Static review is the default and normally the only validation:

- inspect the final diff;
- trace changed types, config, contracts, and call paths;
- check docs and prompt consistency.

Do not automatically run tests, builds, formatters, linters, type checkers,
verification scripts, or smoke commands after an edit.

## Authorization

Executable validation is allowed only when:

- the user explicitly requests it;
- the task is specifically to create or repair tests; or
- static analysis cannot answer a decisive question.

For the last case, ask first. State the exact command, purpose, expected duration,
scope, and any network or permission risk.

## Runtime Budget

When authorized:

1. Run one focused command covering the changed path.
2. Rerun once only after relevant code or environment changed.
3. Ask before widening to package, module, repository, integration, or full
   build scope.
4. Never loop equivalent commands to obtain a passing result.

## Evidence

- Report exact commands run and their result.
- Report tests, builds, or checks intentionally not run.
- Say what remains unverified and why.
- Never imply runtime success from static review alone.
