# workspace-service Specification

## Purpose
TBD - created by archiving change refactor-to-webservice-base. Update Purpose after archive.
## Requirements
### Requirement: Server owns workspace resources
The system MUST provide one server process that owns exactly one workspace context. That context MUST own its mutable SQLite and Lucene resources and MUST be identified by the configured normalized root only within trusted status/configuration output; clients MUST NOT select it through a logical workspace ID.

#### Scenario: All operations use the service workspace
- **WHEN** a valid read or write operation is accepted
- **THEN** it uses the one service-owned workspace context without registry lookup or caller-selected routing

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

### Requirement: Operations remain internal

The system MAY record internal operation state and checkpoints, but MUST NOT require a public operation resource or `operationId` in the first release.

#### Scenario: Restart leaves unfinished work for explicit retry

- GIVEN an operation is interrupted by server shutdown
- WHEN the server restarts
- THEN it does not automatically resume the operation, and a later explicit sync or rebuild can retry idempotently from durable progress

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

### Requirement: Workspace status is observable
The system MUST expose enough health/status information to distinguish process liveness, complete-composition readiness, and the service workspace state, including ready/degraded/failed state, bounded reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time. It MUST NOT expose a selectable workspace inventory.

#### Scenario: Projection is degraded
- **WHEN** the service workspace has a storage or projection recovery problem
- **THEN** service status identifies the degraded component and reason without listing alternate workspaces

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
The system MUST expose overall service state and the current workspace's lifecycle state, reason, queue depth, business checkpoint, projection checkpoint, and last successful projection commit time. It MUST NOT expose a logical workspace ID, display-name registry, selectable inventory, or normalized absolute root in an unauthenticated readiness response.

#### Scenario: Status distinguishes liveness and readiness
- **WHEN** the composition is alive but projection progress is degraded
- **THEN** health remains live, readiness is unavailable, and status reports the current workspace freshness without another workspace identity

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

### Requirement: Saved sources have an explicit recovery outcome

When a content save has durably replaced its source file but a later business or projection step fails, the system MUST identify the result as recoverable. It MUST NOT claim that the file write was rolled back, and an explicit sync MUST remain able to ingest that saved source.

#### Scenario: Save persistence fails after the source write

- GIVEN an editor save has completed the atomic source-file replacement
- WHEN a later persistence step fails
- THEN the response identifies the source path and saved hash as recoverable and instructs the operator to run workspace sync

### Requirement: Document ingestion commits complete business units

The system MUST atomically commit a document's new hash, chunks, derived metadata, related business checkpoint, and pending projection record in SQLite. Preparation and external model calls MUST occur outside this database transaction. A failed transaction MUST retain the prior committed document state. Concurrent readers MUST NOT observe intermediate transaction state.

#### Scenario: Failure occurs while replacing chunks
- **WHEN** ingestion fails after deleting old chunks but before completing the business transaction
- **THEN** the old committed document remains readable and a subsequent sync retries the changed input rather than skipping it because of a prematurely stored hash

#### Scenario: Projection fails after business commit
- **WHEN** the document transaction succeeds but Lucene projection fails
- **THEN** complete business state and a pending projection record remain durable and explicit sync or rebuild can finish projection without duplicating the document

#### Scenario: Input file changes while being prepared
- **WHEN** a source file changes between preliminary change detection and content preparation
- **THEN** the committed content hash and prepared text correspond to the same input bytes

#### Scenario: Editor file write succeeds before business failure
- **WHEN** an editor save writes the source file but subsequent business persistence fails
- **THEN** the operation reports recoverable progress and explicit synchronization can ingest the saved source without claiming the source write was rolled back or duplicating the same content revision

### Requirement: Rebuild publishes only complete search snapshots

The system MUST preserve the most recent usable committed search snapshot throughout a rebuild and MUST publish the replacement only after successful complete index commit. Failed rebuild changes MUST NOT be published by a later unrelated write or resource close.

#### Scenario: Search runs during rebuild
- **WHEN** a rebuild has removed or replaced entries in its uncommitted working state
- **THEN** concurrent search continues using the previous committed snapshot instead of an empty or partial rebuilt index

#### Scenario: Rebuild fails and another write follows
- **WHEN** rebuild fails before successful publication and a later ingestion is accepted
- **THEN** the old snapshot remains usable and the later write cannot accidentally commit partial rebuild contents

### Requirement: Automatic retries require classified faults and safe units

The system MUST limit automatic retries to explicitly classified transient faults and rolled-back or durably idempotent units, with no more than three total attempts. It MUST NOT automatically replay arbitrary partially completed operations or retry permanent validation, permission, constraint, or content-conflict errors.

#### Scenario: Document save conflicts with an external edit
- **WHEN** a save fails with a content conflict
- **THEN** the conflict is returned without automatic replay or duplicate revisions

#### Scenario: SQLite transient lock interrupts a transaction
- **WHEN** a classified transient lock failure rolls back a document transaction
- **THEN** retry may execute the complete transaction within the attempt limit without duplicating committed records

### Requirement: Workspace readiness reflects projection progress

The system MUST report a workspace and overall server as degraded when business progress exceeds available projection progress or pending projection batches remain. Successful catch-up MUST restore readiness unless another lifecycle or storage failure remains. An Article MUST NOT report the new revision as indexed before successful index commit.

#### Scenario: Database commit succeeds but index commit fails
- **WHEN** a document's new business revision commits and index commit fails
- **THEN** status reports projection lag and degraded readiness rather than ready or indexed for that revision

#### Scenario: Explicit retry catches up
- **WHEN** all pending batches have successfully committed and no independent failure remains
- **THEN** workspace and overall readiness return to ready

### Requirement: Writes are serialized for the service workspace
The system MUST route every service-workspace mutation through its bounded FIFO write coordination and MUST NOT retain a registry of independent workspace queues.

#### Scenario: Concurrent writes preserve request order
- **WHEN** two writes are accepted for the service workspace while the first is still running
- **THEN** the second write does not mutate the workspace before the first write completes

