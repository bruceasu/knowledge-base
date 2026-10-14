## ADDED Requirements

### Requirement: Single intent-level retrieval Interface

The system SHALL expose hybrid retrieval through one `retrieve(KnowledgeQuery) -> RetrievalResult` Interface. A query MUST use a repository-confirmed workspace, non-empty query intent, controlled filters, and a bounded result budget; it MUST NOT require callers to provide Lucene syntax, SQL, ripgrep/Ctags/Git flags, or Adapter-specific configuration.

#### Scenario: Retrieve knowledge with a valid workspace query

- **WHEN** a caller supplies a valid workspace, query text, and result budget
- **THEN** the Module returns one `RetrievalResult` containing ordered items, source diagnostics, and completeness state without exposing underlying query protocols

#### Scenario: Reject an invalid retrieval query

- **WHEN** a caller supplies an unknown workspace, empty query text, unsupported filter, or over-budget request
- **THEN** the Module returns a deterministic invalid-query outcome before invoking candidate sources

### Requirement: Traceable Lucene BM25 lexical retrieval

The Module SHALL use `LuceneBm25LexicalRetriever` as a lexical recall Adapter and preserve BM25's source-internal ordering for its own candidates. A Lucene candidate MUST be associated with a stable knowledge object ID, input content hash, and projection version before it can be presented as current knowledge; an unmappable or stale candidate MUST be excluded or explicitly marked with a diagnostic.

#### Scenario: Return a current traceable BM25 candidate

- **WHEN** a Lucene document matches a query and its object ID, content hash, and projection version match current Store state
- **THEN** the result includes the candidate with Lucene provenance and an explanation that it was retrieved lexically by BM25

#### Scenario: Encounter an unmappable legacy Lucene hit

- **WHEN** a legacy Lucene hit lacks the required stable object ID, input hash, or projection version
- **THEN** the Module does not present it as current engineering knowledge and records an appropriate source diagnostic or uses only a separately traceable versioned projection

### Requirement: Deterministic multi-source candidate fusion

The Module MUST normalize bounded candidates from structural Store retrieval, Lucene BM25, ripgrep text search, and Ctags symbols before fusion. It MUST rank by documented freshness, match-category, provenance-completeness, source-internal relevance, and stable fallback keys; it MUST NOT compare raw scores from different sources as if they shared a common scale.

#### Scenario: Rank an exact current symbol above a BM25-only lexical match

- **WHEN** a current Ctags symbol is an exact controlled identifier match and a different current object is only a BM25 lexical match
- **THEN** the exact symbol result ranks first with an explanation of its match category while BM25 ordering remains preserved within its lexical candidate group

#### Scenario: Produce repeatable order from the same candidates

- **WHEN** the same normalized candidates and query are retrieved repeatedly
- **THEN** the resulting item order and rank explanations are identical, including stable tie breaking by object/source identity

### Requirement: Freshness-aware deduplication and evidence aggregation

The Module MUST prefer current canonical objects over derived or stale alternatives. It MUST merge candidates only when they share a stable object identity or stable source location, retain all contributing sources/evidence, and preserve stale/retryable/failed projection state in the returned item or diagnostic. It MUST NOT merge items only because their title or excerpt is similar.

#### Scenario: Merge a structured and lexical candidate for the same object

- **WHEN** Store and Lucene return candidates with the same stable current object ID
- **THEN** the result contains one item with both structural and lexical evidence and a deterministic combined explanation

#### Scenario: Handle a stale lexical projection

- **WHEN** a Lucene candidate's input hash is stale while a current canonical object exists
- **THEN** the current canonical object is preferred and the stale lexical candidate is not silently presented as current

### Requirement: Bounded degradation and Git evidence enrichment

The Module SHALL request bounded work from each source and distinguish an empty source from a failed or truncated one. A failure in an optional source MUST permit remaining sources to return a partial result with diagnostics; Git evidence MUST only enrich an already matched object/source path by default and MUST NOT become an unrelated high-ranked result.

#### Scenario: Return structural results when Lucene fails

- **WHEN** Lucene retrieval fails but structural Store retrieval succeeds for a query
- **THEN** the Module returns the structural results with an incomplete/source-failure diagnostic for Lucene

#### Scenario: Attach recent Git evidence to a matched source path

- **WHEN** a result item has a source path with matching bounded Git evidence
- **THEN** the item includes the Git provenance as enrichment without using commit subject text to infer an engineering relation or outrank an otherwise unrelated result

### Requirement: Preserve existing search and storage contracts

The hybrid Module MUST remain a new application-layer consumer. It MUST NOT alter current `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, legacy `SearchHit`, SQLite schema, existing HTTP/CLI search routing, dependencies, or deployment configuration; it MUST be independently disableable without deleting existing data or indexes.

#### Scenario: Run hybrid retrieval beside the legacy search path

- **WHEN** a focused test or new application-layer consumer invokes `KnowledgeRetriever`
- **THEN** it receives hybrid results without requiring an existing HTTP/CLI caller to change its request/response contract or its current Lucene search path
