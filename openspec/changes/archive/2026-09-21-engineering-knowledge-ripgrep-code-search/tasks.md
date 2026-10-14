## 0. Prerequisite gate

- [x] 0.1 Verify completion evidence for `engineering-knowledge-foundation` and `engineering-knowledge-sqlite-store`, including core source/evidence vocabulary, workspace-root validation, configuration loading, and any existing process-execution seam.
- [x] 0.2 Record the supported `rg` discovery/version policy, unavailable-tool behavior, workspace ignore policy, and resource limits before registering or executing the Adapter.

%% 2026-09-20 0.1 evidence: `.ai/engineering-knowledge-foundation/state.json` and `.ai/engineering-knowledge-sqlite-store/state.json` both report `summary.status = DONE` and `execution = completed`. Core `SourceRef`, `Evidence`, and `EvidenceType.SOURCE_TEXT` can represent source evidence without constructing a `Relation`. The existing HTTP `WorkspaceRegistry` normalizes registered roots but is package-private; `CanonicalPathBoundaryValidator` is a lexical document-content boundary, not an engineering-workspace guard. `TomlConfigLoader` is the existing configuration loader, but has no ripgrep configuration. `CliLlmClient` is the only `ProcessBuilder` precedent and is not a bounded reusable process seam. The Adapter must remain independent of all of these HTTP/legacy services.

%% 0.1 boundary finding: no public engineering-workspace catalog currently exists. A future Adapter request boundary must accept only an explicitly registered, immutable workspace-root mapping; it must not expose or depend on the HTTP package-private registry. The concrete request shape remains WorkItem 1.1.

%% 2026-09-20 0.2 policy record: the controlled development environment reports `ripgrep 12.1.1 (rev 7cb211378a)`. The future Adapter will receive its executable at construction (default literal `rg` resolved only by `ProcessBuilder` on search), will not shell out to discover it, and maps startup failure to an explicit unavailable-tool diagnostic. It relies on ripgrep's default ignore/hidden-file behavior and never exposes `--hidden` or `--no-ignore` to callers. Fixed initial limits are query 512 characters, 64 scoped paths, 3 context lines, 200 results, 4,096 excerpt characters, 1,048,576 stdout bytes, and 5 seconds; `design.md` records the full semantics.

## 1. Constrained Adapter boundary

- [x] 1.1 Define bounded `CodeSearchRequest`, match, diagnostic, and `CodeSearchResult` values at one public `search` seam; reject empty queries, unknown workspaces, out-of-root scopes, and over-limit requests before process launch.
- [x] 1.2 Implement canonical workspace-root/path validation and normalized relative-path output using the confirmed workspace boundary; preserve the repository ignore/hidden-file policy.
- [x] 1.3 Implement shell-free fixed-string ripgrep invocation through `ProcessBuilder` or an existing minimal seam, with no caller-controlled flags or raw command string construction.

## 2. Result and failure semantics

- [x] 2.1 Parse machine-readable ripgrep output into bounded excerpts, match ranges, core-compatible source/evidence data, and deterministic path/line/column ordering.
- [x] 2.2 Implement distinct success-empty, unavailable-tool, unexpected-exit, timeout, malformed-output, encoding-error, and truncated-result diagnostics with required process cleanup.
- [x] 2.3 Ensure text matches remain in-memory evidence candidates only and cannot create engineering relations, SQLite writes, Lucene writes, or a route through current search endpoints.

## 3. Focused behavior tests

- [x] 3.1 Add controlled output fixtures or use an existing minimal process seam to test literal request construction, root confinement, metacharacter handling, and ignore-policy behavior without a platform shell dependency.
- [x] 3.2 Add tests for normalized deterministic ordering, match location/excerpt/source evidence, normal empty results, and non-relation semantics.
- [x] 3.3 Add tests for unavailable tool, unexpected exit, timeout, malformed JSON, output/result truncation, and cleanup/error diagnostics.

## 4. Compatibility and handoff

- [x] 4.1 Statically confirm that this change has not altered SQLite schemas, `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, current ingestion/search behavior, HTTP/CLI contracts, dependencies, or deployment configuration.
- [x] 4.2 Update TODO, Verification, supported-tool evidence, and any remaining design clarification; hand off only the candidate seam to `engineering-knowledge-hybrid-retrieval` after the ctags and Git evidence tasks are complete.

## TODO

- Scope guard: do not add regex mode, arbitrary ripgrep flags, generic command execution, ctags, Git history, Tree-sitter, LSP, Python, persistence, or public MCP/HTTP/CLI exposure here.
- Scope guard: do not replace Lucene BM25 or make current callers depend on ripgrep output directly; hybrid routing remains a later change.

## Verification

- 2026-09-20 focused Adapter test: with `JAVA_HOME=C:\green\jdk-25.0.1+8`, ran `C:\green\apache-maven-3.9.9\bin\mvn.cmd -o -Dtest=RipgrepCodeSearchAdapterTest test`; all 7 tests passed (0 failures, 0 errors, 0 skipped; 0.216 s), with `BUILD SUCCESS`. The suite uses only controlled process fixtures and did not invoke a real ripgrep workspace search.
- 2026-09-20 narrow main compile: with `JAVA_HOME=C:\green\jdk-25.0.1+8`, ran `C:\green\apache-maven-3.9.9\bin\mvn.cmd -o -DskipTests compile`; after one local-variable-name correction, Maven compiled 118 main sources with `BUILD SUCCESS`. Existing JDK native-access/deprecation and unavailable `development` profile warnings remain outside this change.
- 2026-09-20 narrow test-source compile: with the same Java home, ran `C:\green\apache-maven-3.9.9\bin\mvn.cmd -o -DskipTests test-compile`; Maven compiled 32 test sources with `BUILD SUCCESS`, without running tests.
- 2026-09-20 static process/path review: inspected the new Adapter and controlled fixture paths for shell injection, root escape, unbounded output, accidental persistence, and legacy integration. The review found and corrected the `exit 0` plus no-match-output protocol inconsistency; remaining source only creates in-memory `Evidence(SOURCE_TEXT)` candidates. No SQLite schema, `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, HTTP/CLI, dependency, or deployment file was edited.
- 2026-09-20 WorkItem 0.1 prerequisite audit: confirmed the two prerequisite changes are complete and recorded the core evidence vocabulary, current workspace/configuration boundaries, and the lack of a reusable bounded process seam above. No Adapter, configuration, process, database, index, HTTP, or CLI code was changed.
- 2026-09-20 WorkItem 0.2 tool-policy audit: ran `rg --version` locally and recorded `ripgrep 12.1.1 (rev 7cb211378a)`; documented executable discovery, unavailable-tool diagnostics, default ignore/hidden-file behavior, and fixed resource limits. No Adapter search, workspace scan, or production registration was run.
- Handoff state: the candidate seam remains unregistered and is not yet handed to `engineering-knowledge-hybrid-retrieval`; that downstream consumption waits for the ctags and Git evidence changes as required.
