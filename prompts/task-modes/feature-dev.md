# Feature Development

## First
- define the desired behavior before editing
- identify the affected contract, boundary, or user flow
- inspect the extension points already used by the codebase

## Change
- implement the smallest vertical slice that delivers the feature
- follow the local architecture and naming patterns
- call out follow-up work instead of silently expanding scope

## Validate
- add or update tests when they materially protect the new behavior
- use static contract and call-path review by default
- run one narrow check only when the resource budget authorizes it
- update nearby docs when behavior or workflow changes
- ask before widening to cross-boundary or repository checks
