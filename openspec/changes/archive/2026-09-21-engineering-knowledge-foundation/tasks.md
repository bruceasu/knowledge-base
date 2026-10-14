## 1. Repository-grounded inventory

- [x] 1.1 Inspect the current Java modules, packages, public entry points, domain models, ingestion, SQLite persistence, Lucene projection, configuration, event integration, and neighboring tests; create the required architecture inventory with evidence in `architecture-inventory.md`.
- [x] 1.2 Produce the dependency/coupling analysis and `KEEP` / `ADAPT` / `MOVE-LATER` / `REWRITE-LATER` / `DELETE-LATER` / `UNKNOWN` mapping using actual symbols; record unverified items as `UNKNOWN / NEEDS VERIFICATION`.
- [x] 1.3 Map existing `Article`, `ArticleRevision`, chunk, metadata, `SearchHit`, `KnowledgeRepository`, and `LuceneSearchIndex` concepts to the target engineering knowledge vocabulary; identify gaps without changing existing classes.

## 2. Minimal engineering knowledge core

- [x] 2.1 Reconcile the inventory with `design.md`; resolve naming collisions and update the mapping before adding core types. The existing `ask.SourceReference` remains a separate transport DTO from core `SourceRef`.
- [x] 2.2 Add immutable storage-independent knowledge object, source, classification, relationship, and evidence types in the approved core package, enforcing canonical-versus-derived provenance and the initial relation vocabulary.
- [x] 2.3 Add focused tests at the core value-object seam for construction, invariants, provenance, metadata immutability, and evidence-bearing relations.

## 3. Compatibility and handoff

- [x] 3.1 Statically confirm that the change has not modified existing SQLite schema, `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, HTTP/CLI contracts, dependency declarations, or deployment configuration. Only new core classes, their tests, and this change's OpenSpec artifacts were added.
- [x] 3.2 Update the architecture inventory, OpenSpec TODO, Verification records, and remaining `%%` notes with the actual implementation evidence and explicitly identify the next adapter-focused change: `engineering-knowledge-document-adapter`.

## TODO

- [x] Foundation inventory and storage-independent core value-object seam are complete without changing a production path.
- [x] Subsequent changes: `engineering-knowledge-document-adapter`, relation persistence, code knowledge adapters, hybrid retrieval, Context Builder, and MCP exposure remain out of scope for this change.
- [ ] Future service deployment: evaluate a server-side `KnowledgeStore` Adapter only when multi-node, high-availability, or concurrent-write requirements are evidenced.

## Verification

- [x] Static review: confirmed the new core package has no production callers and imports only `java.*`; reviewed the changed-path boundary against existing SQLite, Lucene, HTTP, CLI, configuration, and dependency files.
- [x] Run the focused core test class after explicit authorization: ran `mvn -o -Dtest=KnowledgeObjectTest test` on 2026-09-19 in a temporary `JAVA_HOME=C:\green\jdk-25.0.1+8` / `MAVEN_HOME=C:\green\apache-maven-3.9.4` command environment. Result: 4 tests, 0 failures, 0 errors, 0 skipped; build successful. JDK 25 emitted existing deprecation warnings outside this change.
- [x] Run the narrow Maven compile check after explicit authorization: `mvn -DskipTests compile`.
- [ ] Review existing HTTP/CLI search and synchronization compatibility after the core is implemented; do not claim runtime verification until it has been performed.
