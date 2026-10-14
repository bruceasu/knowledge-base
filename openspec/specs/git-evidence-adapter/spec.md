# git-evidence-adapter Specification

## Purpose
TBD - created by archiving change engineering-knowledge-git-evidence-adapter. Update Purpose after archive.
## Requirements
### Requirement: Constrained local Git evidence request

The system SHALL expose one read-only Git evidence operation that accepts a repository-confirmed workspace, a bounded explicit commit selection or validated commit boundaries, in-workspace file scope, and a result budget. The operation MUST verify that the Git repository contains the workspace and that every selected object is a commit before reading history.

#### Scenario: Inspect an explicit commit in a workspace repository

- **WHEN** a caller supplies a valid workspace and a valid in-repository commit object ID within the budget
- **THEN** the Adapter returns a `GitEvidenceResult` for that commit without exposing raw Git flags or a shell command

#### Scenario: Reject a non-Git workspace or invalid commit selection

- **WHEN** a caller supplies a workspace outside a Git repository, a repository that does not contain the workspace, an invalid object ID, or a non-commit object
- **THEN** the Adapter returns a deterministic diagnostic and performs no history inspection outside the allowed repository boundary

### Requirement: Read-only shell-free Git invocation

The Adapter MUST use parameterized, fixed-profile, read-only Git commands with no pager and MUST NOT invoke a shell, accept caller-provided Git options, contact remotes, or mutate Git state. It MUST apply configured time, commit, file-change, and output-size bounds and clean up a timed-out process.

#### Scenario: Treat command-shaped input as data

- **WHEN** a caller supplies a commit-selection or path value containing flag-like or shell-metacharacter text
- **THEN** the Adapter rejects or handles it only through its validated request grammar and does not execute a shell or caller-controlled Git option

#### Scenario: Enforce a history budget

- **WHEN** a valid commit range would exceed a configured commit, change, byte, or time budget
- **THEN** the Adapter returns a bounded result with an explicit incomplete/truncated diagnostic and no Git mutation

### Requirement: Immutable commit and file-change evidence

For every accepted commit, the Adapter SHALL return its full object ID, parent IDs, configured audit metadata, and normalized file changes. A file change MUST retain its confirmed change kind and workspace-relative old/new paths as applicable; rename/copy, delete, and binary changes MUST not be silently represented as ordinary text modifications.

#### Scenario: Report a rename inside the workspace

- **WHEN** an accepted commit renames a file from one in-workspace path to another
- **THEN** the result records the commit ID, rename change kind, normalized old path, and normalized new path

#### Scenario: Report a binary file change

- **WHEN** an accepted commit changes a binary file in the workspace
- **THEN** the result retains the commit and path-level change evidence without returning or pretending to parse file content

### Requirement: Deterministic path-safe evidence ordering

The Adapter MUST parse file paths with a machine-readable format that preserves whitespace and non-ASCII names. It MUST return commits in documented deterministic topological old-to-new order and changes within each commit in stable normalized-path order. All returned paths MUST be confined to and relative to the registered workspace.

#### Scenario: Inspect changes with spaces or Unicode in paths

- **WHEN** a selected commit changes files whose names contain spaces or non-ASCII characters
- **THEN** the result returns their exact normalized workspace-relative paths without splitting, quoting loss, or path escape

#### Scenario: Request a workspace subdirectory within a repository

- **WHEN** the registered workspace is a subdirectory of the Git repository
- **THEN** the Adapter returns only changes within that subdirectory and normalizes them relative to that workspace rather than the repository top level

### Requirement: Explicit incomplete-history and tool-failure semantics

The Adapter MUST distinguish successful empty history from unavailable Git, unexpected exit, timeout, missing/unreachable objects, malformed machine output, and shallow/incomplete history. It MUST expose these limitations through structured diagnostics and MUST NOT fabricate absent ancestors or report an incomplete history as complete.

#### Scenario: Encounter a shallow history boundary

- **WHEN** a requested history traversal cannot reach an expected ancestor because the local repository is shallow or lacks the object
- **THEN** the result identifies the history limitation and marks the affected result incomplete rather than claiming no earlier changes exist

### Requirement: Evidence-only integration boundary

Git results SHALL be evidence candidates only. The Adapter MUST NOT infer `IMPLEMENTS`, `VERIFIED_BY`, ownership, or any other engineering relation from commit text or file changes; it MUST NOT write SQLite/Lucene data or alter source files, branches, remotes, HTTP/CLI contracts, dependencies, or deployment configuration.

#### Scenario: Inspect a commit with an implementation-like subject

- **WHEN** a commit subject contains words such as “implement” or a requirement-like identifier
- **THEN** the Adapter returns only bounded commit metadata and file-change evidence and does not create an engineering relation or persistence side effect

