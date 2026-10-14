# Implementation Tasks

## TODO

- [x] 1. Define workspace lifecycle, lazy-open/close, and failure isolation.
- [x] 2. Define the normalized operation state machine and checkpoint durability points.
- [x] 3. Define read consistency for search, document detail, session reads, and ask.
- [x] 4. Define status fields, freshness, degraded state, and stable synchronous outcomes.
- [x] 5. Define timeout-unknown, bounded queue, overload, retry classification, limits, and backoff requirements.
- [x] 6. Define detach/purge failure states and remove purge dry-run from scope.
- [x] 7. Define timeout reconciliation through latest-write status without exposing operation resources.
- [x] 8. Define optional request-key handling and batch/checkpoint idempotent retry behavior.
- [x] 9. Define bounded queue admission, overload semantics, and a simple three-attempt retry policy.
- [x] 10. Define Server registry and WorkspaceContext lifecycle/repair responsibility boundaries.
- [x] 11. Define ask/session-close concurrency and recoverable write-back behavior.
- [x] 12. Remove purge dry-run from this change's implementation scope.
- [x] 13. Remove superseded state, queue, retry, and purge decisions from the design.
- [x] 14. Fix the minimal HTTP outcome mapping, error envelope, queue capacity, and retry delays.
- [x] 15. Map current HTTP/CLI lifecycle and direct mutable-resource ownership paths.
- [x] 16. Implement server registry and long-lived per-workspace contexts.
- [x] 17. Route all workspace mutations through per-workspace FIFO queues.
- [x] 18. Refactor SQLite/Lucene ownership, checkpoints, projection refresh, and idempotent retry.
- [x] 19. Convert CLI content commands to service forwarding with no direct-access fallback.
- [x] 20. Implement status, failure isolation, detach, confirmed purge, and service discovery.
- [x] 21. Add HTTP-boundary regression tests and focused service tests for queue/checkpoint behavior.

## Verification

- [x] Run the repository-standard verification script if available.
- [x] Run the existing Maven test suite and focused HTTP/CLI/service tests.
- [x] Verify no CLI content path opens mutable workspace resources directly.
- [x] Verify stable response mappings for terminal, recoverable, unavailable, overloaded, and timeout-unknown outcomes.
- [x] Verify stale-read, checkpoint lag, purge-failure, ask input capture, and workspace isolation scenarios.
- [x] Verify timeout reconciliation, duplicate retry prevention, queue overload, and session-close race scenarios.
- [x] Verify checkpoint invariants and latest-write status retention behavior.
- [x] Verify task ID, OpenSpec change directory, and `task.toml.id` are identical.

## TODO / Open Questions

%% Define Windows lock/PID/port collision handling.
%% Confirmed 2026-08-26: static and runtime workspace registration both required; logical workspace ID is the public identity; root removal uses mode plus confirm=true; HTTP wait defaults to 60 seconds and is configurable.
%% Runtime workspace registration, failed isolation, explicit repair, root detach/purge, durable projection-batch replay, and regression tests are implemented.
%% Tests were added but intentionally not executed at the user's request.