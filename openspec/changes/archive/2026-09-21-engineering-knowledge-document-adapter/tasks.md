## 0. Prerequisite gate

- [x] 0.1 Verified `engineering-knowledge-foundation` tasks 1.1–1.3 and 2.1–2.3 are complete. Its inventory confirms that `ArticleId` is a SQLite-local legacy key, `ArticleRevision.contentHash` is a raw-source-byte hash, and `Chunk` lacks durable identity and generator-version provenance.
- [x] 0.2 Reconciled those findings in this change's design/spec: caller-supplied durable source identity, diagnostic-only output for invalid core source provenance, distinct source-byte versus UTF-8 text hash scopes, and Adapter-owned derived chunk generation are now explicit.

## 1. Adapter boundary and identity policy

- [x] 1.1 Added the single public `LegacyDocumentKnowledgeAdapter.adapt(LegacyDocumentSnapshot)` seam and immutable projection, source-range, diagnostics, and legacy-provenance value types. The input holds existing `Article` and `Chunk` values without modifying them.
- [x] 1.2 Implemented `document:v1` identity from opaque caller-supplied `stableSourceId` and `section:v1` identity from document identity plus normalized heading path and sibling duplicate ordinals.
- [x] 1.3 Implemented core `SourceRef` construction only from supplied logical source fields, kept the legacy revision hash unchanged for the document object, emitted deterministic missing-source diagnostics, and used explicit UTF-8 text hashes for section/chunk objects.

## 2. Structured document mapping

- [x] 2.1 Implemented ATX heading parsing outside backtick/tilde fences, with document roots, canonical sections, parent IDs, heading levels, title paths, and one-based inclusive source ranges.
- [x] 2.2 Kept root content for no-heading and empty documents; unsupported Setext/malformed ATX syntax, skipped heading levels, and unclosed fences return stable diagnostics without synthetic sections.
- [x] 2.3 Mapped every supplied legacy `Chunk` to a derived projection, linking it to the deepest containing section when possible (otherwise the document), carrying the revision input hash and any original generator metadata. The Adapter's own id/version records the projection operation without inventing a legacy generator version.
- [x] 2.4 Implemented only explicit classification or the confirmed relative OpenSpec path rules for `spec.md`, `design.md`, and `.openspec.yaml`; body and title text remain non-authoritative.

## 3. Focused behavior tests

- [x] 3.1 Added compact in-memory `LegacyDocumentKnowledgeAdapterTest` fixtures covering nested, skipped, duplicate, fenced, no-heading, and empty documents.
- [x] 3.2 Added assertions that body-only changes retain the document ID while the source revision hash changes, and that repeated headings get distinct deterministic IDs.
- [x] 3.3 Added assertions for incomplete source diagnostics, derived chunk linkage/provenance, explicit and path-based OpenSpec classification, and unsupported Setext retention.

## 4. Compatibility and handoff

- [x] 4.1 Static boundary review found one public mapping operation and no Adapter reference to `KnowledgeRepository`, `KnowledgeBase`, Lucene, JDBC, HTTP, CLI, or EventBus. No existing production path, schema, dependency, configuration, or deployment file was modified by this change.
- [x] 4.2 Updated design/spec, TODO, and Verification with the actual Adapter contract and static evidence. `engineering-knowledge-sqlite-store` remains the next persistence-focused change and is not implemented here.

## TODO

- [x] No document knowledge object, relation, evidence, or index state is persisted; SQLite schema and transaction work remains with `engineering-knowledge-sqlite-store`.
- [x] The Adapter is not routed through HTTP/CLI or current search; this change adds no Lucene/FTS5 retrieval, Python/external analysis tool, MCP exposure, dependency, or deployment change.

## Verification

- [x] After explicit authorization, run only the focused document-Adapter test class and record the exact Maven command and result.
- [x] After explicit authorization, run the narrowest compile check appropriate to the changed Java module and record the exact command and result.
- [x] Reviewed the completed foundation evidence and statically confirmed the new Adapter has one isolated public mapping operation with no production registration, persistence, index, HTTP, CLI, or EventBus side effect.
