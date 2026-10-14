# Workspace service concurrency

## ADDED Requirements

### Requirement: Server owns workspace resources

The system MUST provide one server process that can manage multiple workspace contexts, and each workspace context MUST own independent mutable SQLite and Lucene resources.

Workspace contexts MUST be registered with a stable logical workspace ID. The server MUST support both startup configuration registration and loopback HTTP registration/unregistration. A normalized filesystem path is status metadata, not the API identity.

#### Scenario: Multiple workspaces remain isolated

- GIVEN workspaces A and B are registered with the same server
- WHEN a write operation runs for workspace A
- THEN workspace B remains independently addressable and its resources are not used by A

### Requirement: Writes are serialized per workspace

The system MUST route every workspace mutation through that workspace's FIFO write queue.

#### Scenario: Concurrent writes preserve request order

- GIVEN two writes are accepted for the same workspace
- WHEN the first write is still running
- THEN the second write does not mutate the workspace before the first write completes

#### Scenario: Different workspaces do not share a write queue

- GIVEN writes are accepted for workspaces A and B
- WHEN A is processing a long write
- THEN B can process its own write without waiting for A

### Requirement: Reads remain available during writes

The system MUST allow search and ask reads to execute without waiting for the workspace write queue.

#### Scenario: Search observes the last available index

- GIVEN a sync is updating a workspace
- WHEN a search request arrives before the new index is committed
- THEN the request returns results from the last available committed index

#### Scenario: Deleted results are not exposed as current documents

- GIVEN a search snapshot contains a document that has since been purged
- WHEN a read resolves that result against current business state
- THEN the service filters it or returns `not found` without exposing deleted content

### Requirement: SQLite is authoritative and Lucene is rebuildable

The system MUST treat SQLite business state as authoritative and MUST permit Lucene to be rebuilt from durable workspace state.

#### Scenario: Lucene update fails after business progress

- GIVEN a write records business progress in SQLite
- WHEN the corresponding Lucene update fails
- THEN the workspace retains the SQLite state, records recoverable progress, and permits explicit retry or rebuild

### Requirement: CLI content operations use the service

The system MUST prevent content commands from directly opening workspace SQLite, Lucene, or mutable content resources.

#### Scenario: CLI sync forwards to the server

- GIVEN the server is available for a workspace
- WHEN the user invokes the existing CLI sync command
- THEN the CLI sends the operation through the server API and preserves synchronous success/failure behavior

### Requirement: Operations remain internal

The system MAY record internal operation state and checkpoints, but MUST NOT require a public operation resource or `operationId` in the first release.

#### Scenario: Restart leaves unfinished work for explicit retry

- GIVEN an operation is interrupted by server shutdown
- WHEN the server restarts
- THEN it does not automatically resume the operation, and a later explicit sync or rebuild can retry idempotently from durable progress

### Requirement: Workspace failures are isolated

The system MUST isolate a failed workspace from healthy workspaces and MUST report an overall degraded state when any managed workspace is unavailable.

#### Scenario: One workspace fails

- GIVEN workspace A cannot open its SQLite or index
- WHEN workspace B receives a valid request
- THEN B continues to serve the request and the server status identifies A as failed/degraded

### Requirement: Root removal is safe by default

The system MUST default root removal to detach and MUST require explicit confirmation for purge.

#### Scenario: Detach preserves historical data

- GIVEN an active root is detached
- WHEN the operation completes
- THEN the root is absent from the active root set while existing records remain recoverable

#### Scenario: Purge remains online

- GIVEN a user explicitly confirms purge
- WHEN purge runs
- THEN it is serialized with other workspace writes while reads remain available against the last valid snapshot

### Requirement: Workspace lifecycle is isolated

The system MUST open workspaces lazily and MUST isolate missing or failed workspaces from the server and from healthy workspaces.

#### Scenario: A missing workspace does not block startup

- GIVEN a registered workspace path does not exist
- WHEN the server starts or another workspace is accessed
- THEN the server remains available, the missing workspace is reported as `missing`, and healthy workspaces continue to operate

### Requirement: Internal write operations have durable states

The system MUST track write progress sufficiently to distinguish queued, running, successful, failed, and recoverable partial states without exposing an operation resource in the first release.

#### Scenario: SQLite succeeds and Lucene fails

- GIVEN a queued write commits business state to SQLite
- WHEN the corresponding Lucene update fails
- THEN the internal state is `partial_failed` or `recoverable`, SQLite state is retained, and an explicit sync or rebuild can retry the projection

### Requirement: Synchronous writes have explicit terminal outcomes

The system MUST preserve synchronous public write behavior and MUST distinguish successful completion, recoverable partial failure, workspace failure, and request timeout.

#### Scenario: Request timeout does not imply cancellation

- GIVEN a write remains queued or running beyond the HTTP timeout
- WHEN the client receives a timeout
- THEN the service does not claim that the write was cancelled unless cancellation is explicitly supported and confirmed

### Requirement: Read consistency is defined per operation

The system MUST use the latest committed Lucene reader for search, current SQLite state for document/session reads, and request-start inputs for ask without holding the write queue during LLM execution.

#### Scenario: Search and document detail observe different snapshots

- GIVEN a write has committed SQLite changes but the new Lucene reader is not available
- WHEN search and document detail are requested
- THEN search may return the previous committed search snapshot while document detail reflects current SQLite state, and stale deleted hits are filtered or returned as `not found`

### Requirement: Service discovery cannot fall back to direct mutation

The system MUST route content operations through the service and MUST NOT reopen SQLite or Lucene directly when service discovery fails.

#### Scenario: Service is unavailable

- GIVEN a CLI content command cannot reach the workspace service
- WHEN the command fails
- THEN it returns a clear service-unavailable result and does not perform a direct local write

### Requirement: Workspace status is observable

The system MUST expose enough health/status information to distinguish overall server health from per-workspace states including `registered`, `opening`, `ready`, `missing`, `degraded`, and `failed`.

#### Scenario: One workspace is degraded

- GIVEN workspace A has a storage recovery problem and workspace B is ready
- WHEN server status is requested
- THEN the response reports a degraded overall state with A's reason while B remains usable

### Requirement: Operation outcomes have stable mappings

The system MUST map internal operation states to stable synchronous outcomes without exposing operation records as public resources.

#### Scenario: Projection is behind business state

- GIVEN SQLite has committed a business change and Lucene has not committed the corresponding projection
- WHEN the synchronous write request completes
- THEN the response identifies a recoverable/partial failure and the workspace status exposes the projection lag

#### Scenario: Operation fails permanently

- GIVEN a write encounters a non-retryable validation or data error
- WHEN the operation terminates
- THEN the response identifies failure and the workspace does not report the operation as succeeded

### Requirement: Business and projection checkpoints are distinguishable

The system MUST persist or expose enough internal state to distinguish the last authoritative SQLite checkpoint from the last Lucene projection checkpoint.

#### Scenario: Explicit retry resumes an incomplete projection

- GIVEN the projection checkpoint lags the business checkpoint
- WHEN the user explicitly invokes sync or rebuild
- THEN processing resumes from a durable idempotent unit and advances the projection checkpoint without duplicating business records

### Requirement: Timeout is distinct from cancellation

The system MUST treat a synchronous request timeout as an unknown client-visible outcome, not as proof that the queued operation was cancelled.

#### Scenario: Client times out while the write continues

- GIVEN a write is queued or running beyond the request timeout
- WHEN the client receives a timeout
- THEN the service may finish the write, and a later status or explicit retry must not assume cancellation occurred

### Requirement: Queue overload and retry are bounded

The system MUST apply a bounded-queue policy with a default capacity of 32 pending writes per workspace and MUST distinguish transient retryable errors from permanent errors. Retry MUST use at most three attempts with default delays of 100 ms, 300 ms, and 900 ms.

#### Scenario: Queue capacity is exhausted

- GIVEN the workspace write queue has reached its configured capacity
- WHEN another write request arrives
- THEN the service returns a stable overload result without mutating the workspace outside the queue

### Requirement: Purge failure is explicit

The system MUST represent purge as an explicit lifecycle and MUST retain a failed root visibly when purge does not complete.

#### Scenario: Purge fails after partial progress

- GIVEN a confirmed purge updates one storage system but fails in another
- WHEN the purge operation terminates
- THEN the root is marked `purge_failed`, remains visible for repair or retry, and is not silently treated as fully deleted

### Requirement: Stable workspace and status fields

The system MUST expose workspace identity fields `id`, display name, normalized path, and lifecycle state. Status MUST distinguish overall server state from workspace state and include reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time.

#### Scenario: Status distinguishes server and workspace state

- GIVEN workspace A is degraded and workspace B is ready
- WHEN status is requested
- THEN the response reports both the overall state and each workspace state with A's reason and freshness fields

### Requirement: Stable synchronous outcomes

The system MUST map writes to `succeeded`, `recoverable`, `failed`, `unavailable`, `overloaded`, or `timeout-unknown`. HTTP MUST use 200 for succeeded/recoverable, 400 for validation/data failure, 409 for state conflict, 413 for overload, 503 for unavailable, and 504 for timeout-unknown. Error responses MUST contain `code`, `message`, `workspaceId`, and `outcome`, with optional `reason`. Timeout MUST NOT imply cancellation or success.

#### Scenario: Queue capacity is exhausted

- GIVEN the workspace queue is full
- WHEN a write is submitted
- THEN the service returns `overloaded` without direct mutation

### Requirement: Timeout retry is idempotent

The system MUST use a default synchronous HTTP wait timeout of 60 seconds. Deployments MAY override this timeout without changing timeout-unknown semantics.

The system MUST distinguish timed-out requests from cancelled writes and MUST use durable checkpoints or equivalent deduplication when a client retries after `timeout-unknown`.

#### Scenario: Client retries after timeout

- GIVEN a write timed out while queued or running
- WHEN the same sync is submitted again
- THEN the service does not apply the same business mutation twice

#### Scenario: Latest write status reconciles an unknown timeout

- GIVEN an accepted write returned `timeout-unknown`
- WHEN the client requests workspace status
- THEN the response exposes the latest write outcome and business/projection checkpoints without exposing an operation resource

#### Scenario: Sync is retried without a request key

- GIVEN a sync request timed out without a client request key
- WHEN the client invokes sync again
- THEN batch/checkpoint idempotency prevents duplicate business records and the projection advances at most once per batch

### Requirement: Ask captures inputs before the LLM call

The system MUST capture search and session inputs at ask start, MUST not hold the workspace write queue during the LLM call, and MUST enqueue the final session message through the workspace FIFO.

#### Scenario: Ask does not block other writes during LLM execution

- GIVEN an ask request is calling the LLM
- WHEN another write arrives
- THEN the other write is not blocked by the LLM call and the final ask message remains serialized

#### Scenario: Session closes before ask write-back

- GIVEN an ask has captured its inputs and is waiting for the LLM
- WHEN the session is closed before the final assistant message is accepted
- THEN the ask returns a recoverable result and does not recreate or reactivate the session

### Requirement: Purge dry-run is out of scope

The system MUST keep detach as the default and require explicit confirmation for purge. Purge dry-run is not part of this change and MUST NOT be required by the first implementation.

Root removal requests MUST use one endpoint with a `mode` field. `mode=detach` is the default and `mode=purge` MUST require `confirm=true`.

#### Scenario: A caller does not request a purge dry-run

- GIVEN a caller needs to preview the impact of removing a root
- WHEN the caller uses this first-release service
- THEN the service does not provide a purge dry-run operation
- AND the caller may use the normal detach or explicitly confirmed purge operation

### Requirement: Status exposes freshness

The system MUST expose enough status data to explain projection freshness and degraded state, including queue depth, business checkpoint, projection checkpoint, last successful projection commit, and a failure reason where applicable.

#### Scenario: Projection lag is observable

- GIVEN SQLite is ahead of Lucene
- WHEN workspace status is requested
- THEN the response identifies the lagging projection and the workspace's current degraded/recoverable state
