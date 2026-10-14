## 0. Prerequisite and schema-safety gate

- [x] 0.1 Verify completion evidence for `engineering-knowledge-foundation` and `engineering-knowledge-document-adapter`, including core IDs, provenance/hash rules, document projection semantics, current schema ownership, migration mechanism, and transaction boundaries.
- [x] 0.2 Confirm that `knowledge-base-reliability-performance` is archived or explicitly frozen for the affected SQLite/Lucene paths, then document the exact migration, rollback, backup/recovery, and compatibility review before implementation starts.

%% 2026-09-20 gate record: `knowledge-base-reliability-performance` was synchronized and archived at `openspec/changes/archive/2026-09-19-knowledge-base-reliability-performance/`; its archive added five requirements to `workspace-service` and four to `document-metadata-search`. `SqliteKnowledgeStore` will own the isolated `engineering-knowledge-v1` forward migration using the existing `schema_migrations` convention, its own JDBC connection, and a short transaction. Before migration, callers stop use of the SQLite file and take a file-level backup; migration failure rolls back without a marker. Post-migration rollback disables the new Store path and retains all tables. Compatibility is preserved because no legacy production caller is routed through the Store and no legacy table, API, dependency, WAL setting, or deployment configuration is changed. A later Adapter integration must define single-writer coordination with the legacy workspace FIFO path.

## 1. Additive SQLite schema

- [x] 1.1 Inspect the confirmed migration convention and add forward-only, repeat-safe migrations for `knowledge_object`, `knowledge_relation`, `knowledge_evidence`, and `knowledge_index_state` plus only the indexes required by their documented access patterns.
- [x] 1.2 Map every core object/provenance field to the new schema and record how canonical/derived state, source references, content hash, generator, input hash, relation endpoints, and relation evidence are represented.
- [x] 1.3 Verify with a focused migration fixture that a new database and an existing database with legacy article/revision/chunk rows reach the target schema without changing legacy data or tables.

## 2. Concrete persistence seam

- [x] 2.1 Implement the concrete SQLite knowledge-storage component and its narrow projection save/read/index-state operations without exposing raw SQL or a premature generic `KnowledgeStore` Interface.
- [x] 2.2 Implement atomic projection persistence with validation for IDs, derived provenance, relation endpoints, and mandatory relation evidence; guarantee rollback on any validation or write failure.
- [x] 2.3 Implement deterministic idempotent replay and content-hash-driven stale/retryable index-state transitions without running Lucene, external commands, or long work in the SQLite write transaction.
- [x] 2.4 Implement recoverable failed index-state recording and bounded reads needed by later Adapters without routing current HTTP/CLI or legacy search calls through the new component.

## 3. Focused persistence tests

- [x] 3.1 Add temporary-database tests for schema initialization, legacy-database upgrade isolation, complete projection save/read, and reopen persistence.
- [x] 3.2 Add tests for evidence-less relations, invalid relation endpoints, missing derived provenance, and transaction rollback with no partial rows or completed state.
- [x] 3.3 Add tests for identical replay idempotence, canonical hash changes marking dependents stale, and recoverable projection failure/retry state.

## 4. Compatibility and handoff

- [x] 4.1 Statically confirm that legacy article/revision/chunk behavior, `KnowledgeRepository`, `KnowledgeBase`, `LuceneSearchIndex`, existing schema, HTTP/CLI contracts, dependencies, and deployment configuration were not modified by the change.
- [x] 4.2 Update this change's TODO, Verification, migration/recovery evidence, and any remaining `%%` notes; hand off only the concrete persistence seam to the later code-Adapter changes.

## TODO

- Completed: prerequisite 0.1 and 0.2 evidence is recorded; implementation may begin only within the documented isolated schema and recovery boundary.
- Completed: added `SqliteKnowledgeStore` as the only new persistence seam, with an isolated forward migration, Store-owned transaction/read boundaries, deterministic relation/evidence IDs, and no production registration.
- Completed: added focused temporary-SQLite tests for migration isolation, complete save/read/reopen, validation and write rollback, idempotent replay, stale transitions, and recoverable failure state.
- Scope guard: do not migrate old documents into the new tables, dual-write legacy data, introduce PostgreSQL/multi-node storage, change WAL settings, or remove any legacy table in this change.
- Scope guard: do not add FTS5, replace Lucene BM25, build retrieval orchestration, add code extraction, expose MCP, or change HTTP/CLI behavior here.

## Verification

- 2026-09-20 static prerequisite review: `.ai/engineering-knowledge-foundation/state.json` and `.ai/engineering-knowledge-document-adapter/state.json` both report `summary.status = DONE` with all mapped work items completed. Reviewed their task/design records for stable IDs, canonical/derived provenance, content-hash scopes, and document projection semantics. Reviewed `KnowledgeRepository` as the current SQLite schema/migration/transaction owner; no source, schema, dependency, or runtime behavior was changed during this review.
- 2026-09-20 schema-safety review: OpenSpec archived `knowledge-base-reliability-performance` after synchronizing its nine added requirements into the two affected main specs. Reviewed the current migration owner and documented the separate `engineering-knowledge-v1` runner, file-backup responsibility, transaction rollback, stop-the-new-path recovery, and compatibility boundary in `design.md`. No legacy schema or runtime path was modified by this review.
- 2026-09-20 narrow compile: with `JAVA_HOME=C:\green\jdk-25.0.1+8`, ran `C:\green\apache-maven-3.9.9\bin\mvn.cmd -o -DskipTests compile`; Maven compiled 109 main sources successfully. Existing deprecation and unavailable `development` profile warnings remain outside this change.
- 2026-09-20 static changed-path review: the implementation adds only `CONTEXT.md`, the concrete `me.asu.kb.store` component and its test, and SQLite Store/change artifacts. It does not edit `KnowledgeRepository`, `KnowledgeBase`, `LuceneSearchIndex`, legacy schema code, HTTP/CLI, dependencies, or deployment settings.
- 2026-09-20 focused persistence/migration test: with `JAVA_HOME=C:\green\jdk-25.0.1+8`, ran `C:\green\apache-maven-3.9.9\bin\mvn.cmd -o -Dtest=SqliteKnowledgeStoreTest test`; all 5 tests passed (0 failures, 0 errors, 0 skipped; 0.386 s), with `BUILD SUCCESS`. Existing native-access, SLF4J-binding, and unavailable `development` profile warnings remain outside this change.
- 2026-09-20 compile evidence: after explicit authorization, ran the narrowest compile check appropriate to the changed Java module and recorded the exact command and result above.
- 2026-09-20 static migration/rollback review: completed against the actual current schema; this does not claim production recovery or concurrency validation without separate runtime evidence.
