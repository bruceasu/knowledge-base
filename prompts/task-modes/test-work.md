# Test Work

## Goal
- test behavior, not implementation details
- protect the public contract or failure mode that matters

## Design
- keep tests deterministic
- prefer small fixtures and focused setup
- create a failing test first when practical
- prefer one focused test that proves the changed contract over a broad suite

## Execution Budget
- a task explicitly about creating or repairing tests authorizes one focused
  test command
- rerun once only after the test or implementation changed
- ask before running a package, module, integration, coverage, or full suite
- do not loop until green or invoke automated review

## Coverage
- cover the changed path
- cover important edge cases and error handling
- avoid broad golden outputs when a smaller assertion is enough
- keep the scope narrow unless the task is specifically about broader coverage

## Report
- state what behavior is now protected
- state what still is not covered
- state the exact focused test result or why it was intentionally left unrun
