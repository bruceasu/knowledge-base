# Go CLI

## Inspect First
- command tree under `cmd/`
- flag and subcommand wiring
- business logic behind commands
- tests for command behavior

## Keep Stable
- flags and subcommands
- exit codes
- printed output
- command package layout
- explicit error handling

## Validate
- use static command wiring, exit code, output, and error-flow review by default
- add tests for behavior and failure paths when CLI behavior changes
- when authorized, run one smallest relevant `go test`, `go vet`, or `go build`
  command for the changed command package
- ask before widening beyond that package
