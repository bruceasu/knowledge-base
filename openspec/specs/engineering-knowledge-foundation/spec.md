# engineering-knowledge-foundation Specification

## Purpose
TBD - created by archiving change engineering-knowledge-foundation. Update Purpose after archive.
## Requirements
### Requirement: Grounded architecture inventory

The system change SHALL produce an architecture inventory grounded in the current repository before adding a production integration to the engineering knowledge model. The inventory MUST identify actual modules, packages, public entry points, domain models, ingestion, indexing, persistence, configuration, event integration, tests, dependency directions, and migration classifications. Any conclusion that cannot be verified from repository evidence MUST be marked `UNKNOWN / NEEDS VERIFICATION`.

#### Scenario: Existing abstraction is classified

- **WHEN** the inventory identifies an existing document, storage, or search abstraction
- **THEN** it records the actual symbol or package, its current role, target role, one of `KEEP`, `ADAPT`, `MOVE-LATER`, `REWRITE-LATER`, `DELETE-LATER`, or `UNKNOWN`, and the evidence for that classification

#### Scenario: Unverified capability is encountered

- **WHEN** the inventory cannot verify an event, extension, configuration, or public contract from the repository
- **THEN** it records `UNKNOWN / NEEDS VERIFICATION` and does not present the capability as an architectural fact

### Requirement: Storage-independent engineering knowledge core

The system SHALL provide immutable engineering knowledge core types that do not depend on SQLite, Lucene, HTTP, MCP, Spring, or external-process types. A knowledge object MUST expose a stable identifier, type, title, optional content, source reference, metadata, knowledge class, and content hash. The core SHALL support `CANONICAL` and `DERIVED` knowledge classes.

#### Scenario: Canonical object is represented

- **WHEN** a caller creates a canonical knowledge object from a repository source
- **THEN** the object retains its source reference and content hash without requiring a storage or search implementation

#### Scenario: Derived object is represented

- **WHEN** a caller creates derived knowledge such as a summary or projection
- **THEN** the object records its canonical source identifier, generator identity and version, and the input content hash used to create it

### Requirement: Evidence-bearing relationships

The system SHALL represent a relationship with stable source and target identifiers, a relation type, and evidence. Evidence MUST record evidence type, source, location, and confidence. The first release MUST limit relation types to `REFINES`, `DESCRIBES`, `IMPLEMENTS`, `VERIFIED_BY`, and `RELATED_TO`.

#### Scenario: Inferred relationship is preserved as non-canonical evidence

- **WHEN** a relationship is derived from static analysis or an LLM-assisted process
- **THEN** its evidence records the derivation source and confidence and does not overwrite a canonical source assertion

### Requirement: Existing retrieval compatibility is preserved

The change SHALL preserve existing article, revision, chunk, SQLite, Lucene BM25, HTTP, and CLI behavior. `LuceneSearchIndex` SHALL remain a rebuildable lexical-search projection and no new production path in this change may require SQLite FTS5, Python, a new dependency, or a service deployment change.

#### Scenario: Existing search path remains available

- **WHEN** the minimal core is added
- **THEN** existing callers continue to use the current search path without depending on the new core types

