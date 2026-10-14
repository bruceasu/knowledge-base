# Go Service

## Inspect First
- `cmd/` entrypoints
- handlers
- services
- storage or client packages
- config packages and tests

## Keep Stable
- package boundaries and `internal/` ownership
- exported APIs
- `context.Context` flow
- concurrency, retry, timeout, and shutdown behavior
- explicit error handling style

## Validate
- use static package, contract, context, and error-flow review by default
- add regression or contract tests when service behavior changes
- when authorized, run one smallest relevant `go test`, `go vet`, or `go build`
  command for the changed package
- ask before widening beyond that package
