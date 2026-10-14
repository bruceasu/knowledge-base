# ripgrep-code-search Specification

## Purpose
TBD - created by archiving change engineering-knowledge-ripgrep-code-search. Update Purpose after archive.
## Requirements
### Requirement: Constrained code-search request boundary

The system SHALL expose code text search through one request/response operation. A request MUST name a repository-confirmed workspace, contain a non-empty literal query, and obey configured bounds for file scope, context, result count, and output size. The operation MUST reject invalid requests without starting an external process.

#### Scenario: Search for an exact identifier in a registered workspace

- **WHEN** a caller supplies a valid registered workspace and a non-empty literal identifier within the configured request limits
- **THEN** the Adapter starts a constrained search and returns a `CodeSearchResult` rather than exposing a raw command or ripgrep arguments

#### Scenario: Reject an invalid workspace or empty query

- **WHEN** a caller supplies an unknown workspace, a path outside the registered root, or an empty query
- **THEN** the Adapter returns a deterministic invalid-request diagnostic and starts no external process

### Requirement: Shell-free workspace-confined invocation

The Adapter MUST invoke ripgrep with a parameterized process API and MUST NOT invoke a shell or concatenate caller-controlled text into a command string. It MUST search only under the validated workspace root, apply the confirmed ignore/hidden-file policy, and return normalized workspace-relative paths only.

#### Scenario: Treat command-shaped query text as literal search data

- **WHEN** a caller supplies a query containing shell metacharacters or flag-like text
- **THEN** the Adapter passes it as literal search data without executing a shell command or enabling caller-supplied ripgrep options

#### Scenario: Prevent a path filter from escaping the workspace

- **WHEN** a caller supplies a file filter that resolves outside the registered workspace
- **THEN** the Adapter rejects the request and does not inspect or return a file outside the workspace root

### Requirement: Deterministic source-evidence results

For each accepted text match, the Adapter SHALL return a normalized relative path, start/end line and column, bounded excerpt, match range, and core-compatible source/evidence data. It MUST sort matches deterministically by relative path, line, column, and stable occurrence order before applying the observable result budget.

#### Scenario: Return two matches in a stable order

- **WHEN** ripgrep reports matches in multiple files or at multiple positions in a file
- **THEN** the result exposes the normalized locations in deterministic path/line/column order regardless of underlying process output order

#### Scenario: Keep a text match as evidence rather than a relation

- **WHEN** a query matches a class name or configuration key
- **THEN** the result includes source evidence for the text occurrence and does not create or assert an `IMPLEMENTS`, `CALLS`, `REFERENCES`, or other engineering relation

### Requirement: Explicit empty, failure, and truncation semantics

The Adapter MUST distinguish a successful empty result from tool-not-found, non-empty unexpected exit, timeout, malformed output, encoding failure, and output/result-budget truncation. A truncated or failed result MUST include a structured diagnostic and MUST NOT be presented as a complete empty result.

#### Scenario: Handle a valid search with no matches

- **WHEN** ripgrep completes with its documented no-match outcome
- **THEN** the Adapter returns a successful empty result without a tool-failure diagnostic

#### Scenario: Handle unavailable or malformed tool output

- **WHEN** ripgrep cannot start, exits unexpectedly, times out, or emits malformed JSON
- **THEN** the Adapter returns a failure diagnostic that identifies the outcome category and does not silently return an empty complete result

#### Scenario: Stop at the configured result budget

- **WHEN** the search would exceed the configured result or output budget
- **THEN** the Adapter returns the bounded subset with an explicit incomplete/truncated indication and performs required process cleanup

### Requirement: Isolate the Adapter from existing retrieval and persistence

The Adapter MUST return in-memory candidates only. It MUST NOT write SQLite knowledge rows, Lucene documents, or legacy search rows, and MUST NOT modify `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, existing HTTP/CLI contracts, dependency declarations, or deployment configuration.

#### Scenario: Exercise code search with a controlled process fixture

- **WHEN** a focused test invokes the Adapter using controlled ripgrep JSON output or an existing minimal process seam
- **THEN** the test verifies requests, results, and diagnostics without needing a web server, Lucene, a database write, or a change to an existing search endpoint

