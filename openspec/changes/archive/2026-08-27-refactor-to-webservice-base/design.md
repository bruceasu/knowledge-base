# Design: web service ownership and workspace concurrency

## Context

The service currently opens workspace resources around individual operations. A long-lived server must own SQLite and Lucene resources so concurrent requests cannot create competing writers or inconsistent lifecycles.

## Runtime topology

```text
Server
  -> workspace registry
  -> WorkspaceContext per workspace
       -> SQLite repository
       -> Lucene projection owner
       -> bounded FIFO write queue
       -> lifecycle/status/checkpoint state
  -> HTTP and CLI integration boundary
```

The Server opens workspaces lazily. A missing or failed workspace is isolated and does not prevent healthy workspaces from starting or serving requests. Workspaces may be supplied statically at startup and may also be registered or unregistered through the loopback HTTP API. Each workspace has a stable logical ID; normalized paths are status metadata and are not API identities.

## Concurrency model

All mutations for one workspace pass through its FIFO queue. Reads do not wait for the queue: search uses the latest committed Lucene reader, while document and session reads use current SQLite state. Different workspaces have independent queues and may run concurrently.

The normalized internal write state is:

```text
queued -> running -> succeeded
                 -> failed
                 -> partial_failed -> recoverable -> succeeded|failed
```

SQLite and Lucene commit positions are checkpoints, not operation states. Operations remain internal; the first release exposes no public operation resource.

## Storage and recovery

SQLite is authoritative for business state, root state, session state, and checkpoints. Lucene is a rebuildable projection. A write records a durable batch containing the affected paths, `businessCheckpoint`, and projection completion marker; retry resumes pending batches in ascending batch order and replaces each path's Lucene projection idempotently. Status exposes the pending-batch count.

If SQLite is unavailable, the workspace rejects mutations as `unavailable`. Recovery is explicit reopen/rebuild; the server does not automatically resume unfinished work after restart.

## Queue, timeout, and retry policy

The queue is bounded with a deployment-configured small capacity. A full queue rejects before mutation and returns `overloaded`. An accepted write that exceeds the HTTP timeout continues in the queue and returns `timeout-unknown`; timeout does not cancel the write. The default HTTP wait timeout is 60 seconds and may be overridden by deployment configuration.

The default retry policy is at most three attempts with bounded increasing delay. Temporary storage contention and temporary Lucene/file failures are retryable. Validation errors and missing required data are permanent failures. Exhausted retryable failures become `recoverable` when explicit sync/rebuild can continue; otherwise they become `failed`.

Writes may carry an optional client request key. Without one, sync and rebuild rely on batch/checkpoint idempotency rather than per-request lookup. The default queue capacity is 32 pending writes per workspace. Retry delays are 100 ms, 300 ms, and 900 ms for the three attempts.

## Stable service contracts

Workspace status includes workspace identity, lifecycle state, reason, queue depth, latest accepted write outcome, `businessCheckpoint`, `projectionCheckpoint`, and last successful projection commit time. The latest outcome is retained until the next accepted write or server restart.

Write outcomes are `succeeded`, `recoverable`, `failed`, `unavailable`, `overloaded`, and `timeout-unknown`. The HTTP mapping is: `200` for succeeded/recoverable, `400` for failed validation/data input, `409` for failed state conflict, `413` for overloaded, `503` for unavailable, and `504` for timeout-unknown. Error responses use `{code, message, workspaceId, outcome}` with optional `reason`; CLI preserves its existing synchronous exit behavior.

Root requests contain root identity and `mode`. Detach is the default. `mode=purge` requires `confirm=true`, runs through the queue, keeps reads available, and uses `purging`, `purge_failed`, and completed/detached states. Purge dry-run is out of scope.

## Ask consistency

`ask` captures search results and session input at request start, releases the workspace queue during the LLM call, and submits the final assistant message as a normal FIFO write. If the session closes before write-back is accepted, ask returns `recoverable` and does not recreate or reactivate the session.

## Ownership boundaries

- Server owns registry and workspace lifecycle coordination.
- WorkspaceContext owns resource open/close, lifecycle state, queue admission, and status snapshots.
- Repository owns SQLite transactions and business checkpoints.
- Projection owner owns Lucene writer, commit, reader refresh, and projection checkpoints.
- CLI adapter forwards content commands to HTTP and never falls back to direct mutable-resource access.

## Test seam

The primary seam is the HTTP application boundary. Tests observe response contracts, visibility, ordering, isolation, stale reads, and recovery. Service-level tests are limited to deterministic queue/checkpoint invariants that cannot be reliably observed through HTTP.

## Remaining implementation-level notes

Queue capacity and retry delays may be overridden by deployment configuration without changing the contract.
%% Define Windows stale-lock, PID-reuse, and port-collision handling.
