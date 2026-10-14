# Python Service

## Inspect First
- routers or handlers
- services or use-cases
- schemas or models
- config or settings
- tests near the touched behavior

## Keep Stable
- request and response shapes
- validation behavior
- config and environment loading
- persistence and client boundaries
- async, retry, timeout, and shutdown behavior

## Validate
- use static schema, typing, config, and request-flow review by default
- add regression or contract tests when API behavior changes
- when authorized, run one smallest relevant `ruff`, `mypy`, or `pytest`
  command for the changed package or behavior
- ask before widening beyond that package
