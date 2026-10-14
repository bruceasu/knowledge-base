# Java Spring

## Inspect First
- controllers or handlers
- services or domain components
- repositories
- DTOs and validation rules
- config and tests

## Keep Stable
- controller, service, and repository boundaries
- request and response contracts
- transaction boundaries
- dependency injection style
- serialization and validation behavior

## Validate
- use static controller, service, transaction, schema, and config review by
  default
- add unit, slice, or integration tests that match the changed boundary
- when authorized, run one smallest relevant Maven or Gradle command for the
  changed module or boundary
- ask before widening beyond that module
