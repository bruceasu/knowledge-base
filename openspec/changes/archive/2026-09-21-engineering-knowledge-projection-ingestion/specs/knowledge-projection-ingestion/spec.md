## ADDED Requirements

### Requirement: Single bounded ingestion Interface

The system SHALL expose one `ingest(IngestionRequest) -> IngestionReport` Interface for engineering knowledge projection ingestion. The request MUST identify a repository-confirmed workspace, explicitly supported source categories and bounded source scopes; it MUST be rejected before Adapter or Store work when identity, scope, budget, or source category is invalid.

#### Scenario: Ingest a bounded document source selection

- **WHEN** a caller supplies a valid workspace and a supported bounded document source selection
- **THEN** the Module returns one `IngestionReport` that describes the source-unit outcomes without exposing Adapter internals, SQL, transactions, or external-tool commands

#### Scenario: Reject an unsupported source category

- **WHEN** a caller requests ripgrep text matches, an unknown source category, an out-of-root source, or an over-budget selection
- **THEN** the Module reports a deterministic invalid/unsupported outcome and does not start an Adapter or Store transaction for that unit

### Requirement: Normalize only verified Adapter output

The Module MUST normalize document, Ctags-symbol, and Git-evidence Adapter output into core-compatible objects, source references, evidence, and derived provenance only when the required stable source identity, input hash, source location, and generator/profile information are present. It MUST preserve canonical/derived semantics and MUST NOT infer engineering relations from titles, text matches, symbol metadata, Git subjects, or file changes.

Candidate evidence that does not support a relation MUST be retained as bounded reserved object-provenance metadata in the existing schema. It MUST NOT be inserted into relation-owned `knowledge_evidence` by fabricating a relation.

#### Scenario: Persist a document projection with provenance

- **WHEN** the document Adapter supplies a valid document/section projection with confirmed source identity and revision content hash
- **THEN** the Module supplies the corresponding canonical/derived objects and provenance to the Store without changing the legacy article/revision/chunk source

#### Scenario: Ingest a Ctags symbol with its profile

- **WHEN** the Ctags Adapter supplies a valid Java symbol candidate and its source content hash/profile
- **THEN** the Module represents it as the confirmed derived form with source and generator provenance and does not create an `IMPLEMENTS`, `CALLS`, or other inferred relation

#### Scenario: Reject an unbound Ctags result

- **WHEN** symbol candidates do not carry an extractor-verified source content hash or the source changes during extraction
- **THEN** the Module reports the unit failed and does not construct or persist a canonical Java-source input or derived symbol

### Requirement: Atomic source-unit persistence and explicit partial outcomes

For each source unit, the Module MUST complete extraction/normalization before invoking the SQLite Store's atomic save operation. A failed unit MUST leave no partial object, relation, evidence, or completed index state for that unit. Independent units MAY complete separately, but the report MUST list every unit as saved, unchanged, stale, failed, or unsupported with diagnostic evidence.

#### Scenario: One of two source units fails

- **WHEN** two independent source units are requested and one fails validation or Store persistence after the other has saved successfully
- **THEN** the successful unit remains committed, the failed unit has no partial persisted state, and the report records both outcomes explicitly

### Requirement: Hash/profile-driven idempotence and staleness

The Module SHALL compare stable source identity, input content/tree/commit hash, and Adapter generator/profile version to determine whether a unit is unchanged or requires a new save. It MUST use the Store's stale/retryable state path when relevant input or profile changes and MUST NOT run Lucene indexing, external work, or long parsing inside a SQLite write transaction.

The concrete Store SHALL support bounded profile replacement in the same transaction as the source-unit save so derived objects and index states owned by an older generator/profile version cannot remain current after a successful replacement. This operation MUST NOT require a schema change.

#### Scenario: Re-ingest an unchanged source unit

- **WHEN** a source unit has the same stable identity, input hash, and generator/profile version as its last committed projection
- **THEN** the Module reports it unchanged and does not create duplicate persisted objects or evidence

#### Scenario: Re-ingest a source with changed input or profile

- **WHEN** a document revision, Java source input, or Ctags profile changes for an existing source unit
- **THEN** the Module saves the revised projection and marks affected derived/index state stale or retryable through the Store without rebuilding Lucene in the write transaction

### Requirement: Explicit non-registration with legacy runtime paths

The initial ingestion Module MUST be invoked only through its explicit application-layer Interface. It MUST NOT automatically attach to existing article synchronization, editor saves, filesystem events, event bus, scheduled jobs, HTTP/CLI, MCP, or Lucene indexing, and it MUST NOT introduce a legacy double-write path.

#### Scenario: Create and use the Module in a focused test

- **WHEN** a focused test calls `ingest` with controlled Adapter fixtures and a temporary SQLite Store
- **THEN** the test can verify the resulting report and stored projection without registering an existing runtime trigger or changing an HTTP/CLI endpoint

### Requirement: Preserve legacy and query-time boundaries

The Module MUST NOT modify existing article/revision/chunk tables, `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, existing search/synchronization behavior, dependencies, or deployment configuration. It MUST NOT persist ripgrep query-time matches or make any new public retrieval promise.

#### Scenario: Ingest structured sources while text search remains query-time only

- **WHEN** a request includes supported structured sources and a caller separately performs a ripgrep text search
- **THEN** only the structured sources are eligible for this Module's Store write and the text-search result remains an in-memory query candidate
