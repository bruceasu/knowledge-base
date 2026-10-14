## ADDED Requirements

### Requirement: Additive isolated knowledge schema

The system SHALL create `knowledge_object`, `knowledge_relation`, `knowledge_evidence`, and `knowledge_index_state` through the repository-confirmed versioned SQLite migration mechanism. The migration MUST be forward-only, repeat-safe, and isolated from existing article, revision, chunk, metadata, search, and workspace tables.

#### Scenario: Initialize a new database

- **WHEN** the application initializes a new SQLite database at the target migration version
- **THEN** all four knowledge tables and only their required indexes are available for the knowledge Store

#### Scenario: Upgrade an existing knowledge-base database

- **WHEN** the migration runs against a database containing existing article/revision/chunk data
- **THEN** the existing tables and rows remain readable with their prior semantics and the new knowledge tables are added without migrating or rewriting old rows

### Requirement: Atomically persist an evidence-bearing projection

The concrete SQLite knowledge Store MUST persist a supplied knowledge projection in one short transaction. Before commit, it MUST validate object identities, every derived object's canonical source/generator/input-hash provenance, every relation endpoint, and at least one persisted evidence item for every relation.

#### Scenario: Save a valid projection

- **WHEN** a projection contains valid canonical/derived objects, evidence-bearing relations, and index-state updates
- **THEN** the Store commits objects, evidence, relations, and state together and a subsequent read returns the complete committed projection

#### Scenario: Reject a relation without evidence

- **WHEN** a caller attempts to save a relation that has no evidence
- **THEN** the Store rejects the operation and commits none of that operation's object, relation, evidence, or index-state changes

#### Scenario: Reject an invalid endpoint or derived source

- **WHEN** a projection references an unknown relation endpoint or a derived object lacks its required canonical source, generator, or input hash
- **THEN** the Store rejects the operation without leaving a partial knowledge row or index state

### Requirement: Preserve canonical and derived lifecycle semantics

The Store SHALL retain canonical-versus-derived classification, source provenance, content hash, generator information, and input hash. When a canonical object's relevant content or source hash changes, the Store MUST mark dependent derived objects and affected projection/index state stale or retryable in the same committed transaction; it MUST NOT run indexing or external processing inside that transaction.

#### Scenario: Update canonical content with a dependent projection

- **WHEN** a canonical object changes from one content hash to another and a derived object or index state depends on the old hash
- **THEN** the canonical update commits with the dependent entry marked stale or retryable and no Lucene rebuild or external command executes during the save

### Requirement: Idempotent replay and recoverable projection state

The Store MUST make a replay of the same stable object IDs, content hashes, generator versions, relations, and evidence idempotent. `knowledge_index_state` MUST distinguish at least committed/current, stale-or-retryable, and failed/recoverable projection outcomes with sufficient input identity to decide whether a rebuild is valid.

#### Scenario: Replay identical input

- **WHEN** the Store receives the same complete projection twice
- **THEN** it does not create duplicate objects, relations, or evidence and the observable stored state is equivalent to one successful save

#### Scenario: Recover from a projection failure

- **WHEN** a projection operation records a recoverable failure after canonical data has committed
- **THEN** the canonical data remains readable and the index-state record identifies the failed/stale input so a later worker can retry without inventing a new canonical object

### Requirement: Read through a concrete storage seam without legacy side effects

The system SHALL expose store read/write behavior through a concrete knowledge-storage component rather than direct SQL in callers. That component MUST NOT modify existing article/revision/chunk rows, `KnowledgeRepository`, `KnowledgeBase`, `LuceneSearchIndex`, HTTP/CLI contracts, dependency declarations, or deployment settings.

#### Scenario: Exercise the Store against a temporary database

- **WHEN** a focused test saves and reads a knowledge projection using a temporary SQLite database
- **THEN** the test can verify persistence and recovery behavior without invoking an existing article service, HTTP server, CLI command, Lucene index, or external process

### Requirement: Non-destructive rollback boundary

The knowledge storage path MUST be independently disableable after migration. Disabling it MUST stop new knowledge writes and projection consumption without deleting the new tables or altering legacy tables/data.

#### Scenario: Disable the new knowledge path after deployment

- **WHEN** operators stop routing new work through the knowledge Store following a failure
- **THEN** existing article/revision/chunk functionality continues on the legacy path and the new tables remain available for inspection or controlled recovery
