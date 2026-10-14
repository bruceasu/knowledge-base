# Python CLI

## Inspect First
- command entrypoints
- argument parsing
- command handlers
- packaging and install entrypoints
- tests for command behavior

## Keep Stable
- flags and subcommands
- exit codes
- stdout and stderr behavior
- packaging and entrypoint patterns

## Validate
- use static command wiring, exit code, output, and error-flow review by default
- add tests for behavior and failure paths when CLI behavior changes
- when authorized, run one smallest relevant `ruff`, `mypy`, or `pytest`
  command for the changed command or package
- ask before widening beyond that package
