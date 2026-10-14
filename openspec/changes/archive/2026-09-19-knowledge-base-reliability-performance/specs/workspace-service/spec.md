## ADDED Requirements

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

### Requirement: Workspace lifecycle does not block unrelated workspaces

The system MUST coordinate opening and closing independently per workspace. It MUST NOT hold a registry-wide lock while performing storage initialization, waiting for operations, or closing resources. Accepted read and write operations MUST retain resource ownership until their actual completion.

#### Scenario: Workspace A closes slowly
- **WHEN** A waits for an accepted operation during shutdown
- **THEN** B remains independently addressable for reads, writes, and status requests

#### Scenario: Close wait expires
- **WHEN** the close wait expires while an accepted operation still uses storage
- **THEN** the workspace rejects new operations, retains the resources needed by accepted operations, and releases them only after those operations finish

#### Scenario: Repeated close and open requests race
- **WHEN** repeated lifecycle requests target a closing workspace
- **THEN** resource release is idempotent and a replacement instance cannot overlap ownership of the same mutable storage

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
