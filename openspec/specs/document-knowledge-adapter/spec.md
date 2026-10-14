# document-knowledge-adapter Specification

## Purpose
TBD - created by archiving change engineering-knowledge-document-adapter. Update Purpose after archive.
## Requirements
### Requirement: Single read-only document mapping seam

The system SHALL provide one public document-adaptation operation that accepts a legacy document snapshot and returns a `DocumentKnowledgeProjection`. The operation MUST treat the legacy snapshot as read-only and MUST NOT require existing `Article`, `ArticleRevision`, or `Chunk` types to implement the engineering knowledge core.

#### Scenario: Adapt an existing document snapshot

- **WHEN** a caller supplies a legacy snapshot containing a stable source identity, revision content, source metadata, and any existing chunk projection
- **THEN** the Adapter returns one complete `DocumentKnowledgeProjection` without changing the supplied legacy values or invoking persistence, indexing, synchronization, HTTP, or CLI behavior

### Requirement: Preserve document and heading structure

The Adapter SHALL represent the input as one document root plus zero or more section entries that preserve supported Markdown/OpenSpec heading hierarchy. Each section MUST expose its logical parent, heading level, title path, and an available source range; text that resembles a heading inside a fenced code block MUST NOT create a section.

#### Scenario: Map nested headings

- **WHEN** an input document contains supported nested headings and body text
- **THEN** the returned projection contains a root object and sections whose parent links and heading levels match the heading hierarchy and whose source ranges point into the input revision

#### Scenario: Ignore a heading-shaped line in a code fence

- **WHEN** an input document contains a fenced code block with a line that has Markdown heading syntax
- **THEN** that line remains body content and the projection does not create a section for it

#### Scenario: Preserve a document without headings

- **WHEN** an input document contains no supported heading
- **THEN** the projection contains the document root, contains no synthetic section, and retains the content and source information needed to locate the document

### Requirement: Deterministic identity and provenance

The Adapter MUST derive the document canonical ID from the stable source identity confirmed by `engineering-knowledge-foundation`; it MUST NOT derive that ID solely from a title, an absolute local path, or a content hash. It MUST derive unique, deterministic section IDs from the document identity, normalized heading path, and duplicate-heading discriminator. Every projected object MUST carry the core-defined source/provenance and content-hash information available from the input.

#### Scenario: Keep a document identity across a body-only revision

- **WHEN** two snapshots have the same confirmed stable source identity and heading structure but different body text
- **THEN** their document objects have the same canonical ID while their revision content hashes reflect the different content

#### Scenario: Distinguish repeated headings

- **WHEN** two sibling sections have the same heading text under the same parent
- **THEN** the projection assigns distinct deterministic section IDs and retains the source range for each occurrence

#### Scenario: Report incomplete source provenance honestly

- **WHEN** a legacy snapshot lacks a source field needed by the core provenance contract
- **THEN** the Adapter reports the missing provenance through its deterministic diagnostics and does not fabricate a path, revision, or full-confidence source reference

#### Scenario: Do not emit objects without a core-valid source

- **WHEN** a legacy snapshot lacks either the source system or source locator required by `SourceRef`
- **THEN** the Adapter returns a diagnostic-only `DocumentKnowledgeProjection` with no document, section, or derived chunk object
- **AND** it retains no fabricated fallback source identity

### Requirement: Keep fixed-size chunks derived

When a legacy snapshot contains fixed-size chunk data, the Adapter SHALL expose it only as a derived projection. Each derived chunk entry MUST retain its source document or section identity when available, generator information when available, and the input content hash; it MUST NOT replace the document or sections as the projection's primary semantic structure.

#### Scenario: Carry forward a legacy chunk as derived data

- **WHEN** a legacy snapshot includes one or more existing chunks
- **THEN** the projection marks each corresponding entry as derived and links it to available provenance rather than representing it as the only canonical document object

#### Scenario: Preserve unknown legacy chunk generation honestly

- **WHEN** a legacy chunk has no recorded original generator identity or version
- **THEN** the Adapter records itself as the generator of the derived projection, emits a deterministic missing-legacy-generator diagnostic, and does not claim an unrecorded `TextChunker` version

### Requirement: Bounded syntax classification and diagnostics

The Adapter MUST classify an OpenSpec artifact only from explicit source metadata or a repository-confirmed path/file-name rule. It MUST use deterministic behavior for unsupported heading forms, skipped heading levels, empty content, and malformed input, retaining non-discarded text and returning diagnostics where structure cannot be represented completely. It MUST NOT infer requirements, decisions, designs, or implementation relations from natural-language content or an LLM.

#### Scenario: Classify a known OpenSpec artifact from source metadata

- **WHEN** a source snapshot explicitly identifies a repository-confirmed OpenSpec artifact kind
- **THEN** the projection uses the corresponding confirmed classification without depending on title or body-word heuristics

#### Scenario: Handle unsupported structure without data loss

- **WHEN** an input includes a heading construct outside the Adapter's confirmed supported grammar
- **THEN** the Adapter preserves the affected text under the applicable document or section content and returns a deterministic diagnostic instead of silently dropping or inventing structure

#### Scenario: Classify only through documented path rules

- **WHEN** a source snapshot has no explicit artifact classification but has a repository-relative path matching a confirmed OpenSpec rule
- **THEN** the Adapter classifies only `openspec/**/specs/*/spec.md` as `REQUIREMENT`, `openspec/**/design.md` as `DESIGN`, and `openspec/**/.openspec.yaml` as `CONFIGURATION`
- **AND** it leaves all other paths as a generic document regardless of title or body wording

### Requirement: Preserve existing knowledge-base behavior

The document Adapter implementation MUST NOT modify the existing article/revision/chunk schema, `KnowledgeRepository`, `KnowledgeBase`, `LuceneSearchIndex`, current ingestion and search paths, HTTP/CLI contracts, dependency declarations, or deployment configuration. The Adapter output MUST remain in-memory until a later, separately approved persistence change consumes it.

#### Scenario: Run the Adapter in isolation

- **WHEN** a test invokes the Adapter using an in-memory fixture
- **THEN** the test can verify the returned projection without requiring SQLite, Lucene, external commands, a web server, or modification of an existing knowledge-base entry point

