## ADDED Requirements

### Requirement: Virtual projects group local resource references

The service MUST provide HTTP operations to create, read, update, and remove virtual projects. A virtual project MUST have a stable identifier and metadata stored in the shared knowledge workspace, and MUST support multiple file and directory references. References MAY point anywhere on the local machine when permitted by the configured path policy. Removing a membership or virtual project MUST NOT delete its referenced source files.

#### Scenario: Create a project with multiple resource references
- **WHEN** an operator creates a virtual project and adds files or directories from multiple local locations
- **THEN** the service stores the project metadata and references without copying or moving the source files

#### Scenario: Remove a project without deleting source data
- **WHEN** an operator removes a virtual project or one of its resource references
- **THEN** the service removes its metadata/index membership and leaves the referenced local files unchanged

### Requirement: Path access uses allow and forbid rules

The service MUST apply one configured local path policy before adding a resource, scanning, synchronizing, reading, or modifying a project file. A matching forbid rule MUST take precedence over any matching allow rule. The policy MUST evaluate canonical targets so path normalization or symbolic links cannot bypass a forbidden location. A denied path MUST NOT be read, indexed, or modified, and the response MUST provide a sanitized denial result.

#### Scenario: Forbid overrides allow
- **WHEN** a requested resource matches both an allow rule and a forbid rule
- **THEN** the service denies the operation before reading or modifying the resource

#### Scenario: Symbolic link targets a forbidden location
- **WHEN** an allowed directory contains a symbolic link whose canonical target matches a forbid rule
- **THEN** scanning and file operations do not follow the link into the forbidden location

#### Scenario: Deny an external path that is not permitted
- **WHEN** a virtual project references a local path that does not satisfy the active path policy
- **THEN** the service leaves it unindexed and returns a sanitized path-policy diagnostic

### Requirement: Project indexes are separate derived data

The service MUST maintain each virtual project's searchable index in a service-owned project-specific directory separate from the shared knowledge index. The index MUST be rebuildable from project metadata and current source references. The service MUST use suitable existing document or code extraction mechanisms without requiring one identical index strategy for every file type. An unreadable or unsupported file MUST be reported as unindexed or incomplete and MUST NOT be represented as successfully searchable content.

#### Scenario: Build project indexes from referenced content
- **WHEN** a valid project synchronization processes supported documents and source files
- **THEN** it updates that project's derived index without copying source files into service-owned storage

#### Scenario: Encounter an unsupported file type
- **WHEN** a referenced file cannot be read or has no supported extractor
- **THEN** the project sync report marks it unsupported or incomplete and does not claim it is searchable

### Requirement: Explicit and scheduled synchronization share one operation

The service MUST expose an explicit HTTP operation to synchronize a virtual project and MUST support scheduled synchronization. Both triggers MUST use the same bounded, idempotent application operation. Referenced directories MUST be rescanned during synchronization so newly added files can be discovered. Removed or changed resources MUST be reconciled so stale candidates are not silently returned as current. Synchronization MUST expose per-project outcome, freshness, and diagnostics.

#### Scenario: Explicitly synchronize a project
- **WHEN** an authorized caller requests synchronization for a valid virtual project
- **THEN** the service processes its current references and returns a bounded synchronization report

#### Scenario: Scheduled synchronization finds new files
- **GIVEN** a project references a directory and a new supported file has been added under it
- **WHEN** the scheduled project synchronization runs
- **THEN** the new file becomes searchable in that project's index with source provenance

#### Scenario: Synchronization encounters a failure
- **WHEN** part of a project cannot be scanned or indexed
- **THEN** the service preserves usable results, marks the project incomplete, and reports the failed source without claiming a complete sync

### Requirement: Project-aware search merges shared and project results

The search application MUST search only shared knowledge when no virtual-project identifier is supplied. When a valid virtual-project identifier is supplied, it MUST search both shared knowledge and that project's index, merge the candidates deterministically, and give project candidates an additional ranking preference. It MUST NOT call an LLM to select, merge, or rank ordinary search results. Results MUST preserve source scope, virtual-project identity where applicable, original path, available symbol/line location, freshness, and source diagnostics.

#### Scenario: Search shared knowledge by default
- **WHEN** a caller searches without a virtual-project identifier
- **THEN** the service returns candidates from the shared knowledge scope only

#### Scenario: Search shared and selected project knowledge
- **WHEN** a caller searches with a valid virtual-project identifier
- **THEN** the service merges shared and selected-project candidates and applies deterministic additional ranking preference to project candidates

#### Scenario: Preserve source identity in merged results
- **WHEN** shared and project indexes return candidates for one query
- **THEN** each result identifies its source scope and retains its traceable path or supported code location

#### Scenario: Project index is unavailable
- **WHEN** the selected project index is unavailable but shared retrieval succeeds
- **THEN** the service returns shared results with an incomplete diagnostic for the project source

### Requirement: Agent file operations are bounded and policy checked

The service MUST expose HTTP operations for an Agent to create, append, replace, edit, and delete files associated with a virtual project. Every operation MUST resolve the project membership and pass the same allow/forbid policy as synchronization. Operations MUST return an explicit success or failure outcome. A file operation MUST NOT modify a denied path or a resource outside the selected virtual project's authorized references. Agent Memory files MUST use these same ordinary project file operations and index lifecycle without a separate Memory store.

#### Scenario: Agent creates or updates a project file
- **WHEN** an Agent invokes a supported file operation on an allowed resource associated with a virtual project
- **THEN** the service performs the requested operation and returns an explicit outcome

#### Scenario: Agent attempts to modify a resource outside the project
- **WHEN** an Agent supplies a path not associated with the selected virtual project
- **THEN** the service rejects the operation before modifying the file

#### Scenario: Agent Memory uses ordinary project files
- **WHEN** an Agent creates or edits a Memory file inside an allowed project scope
- **THEN** the file is handled by the same storage, synchronization, and search rules as other project files

### Requirement: Git repositories are discovered as source metadata

The service MUST detect a Git repository root for referenced files when one is available and MUST return that identity as source metadata. Git detection MUST NOT require repository registration and MUST NOT change project membership or path authorization.

#### Scenario: Index a file under a Git repository
- **WHEN** a referenced file is under a locally detectable Git worktree
- **THEN** results may identify its repository root and repository-relative path without requiring a repository registration operation

#### Scenario: Index a non-Git file
- **WHEN** a referenced file is not under a detectable Git worktree
- **THEN** the file remains eligible for indexing under the same path policy and is identified by its local source path

### Requirement: Project management remains local and UI-free

The capability MUST operate within the single service process and configured shared workspace. It MUST provide programmatic HTTP operations and MUST NOT require a Web UI, remote service, real-time file watcher, LSP, or Codex SDK.

#### Scenario: Manage a project in the local service
- **WHEN** an operator creates or synchronizes a project through a supported HTTP operation
- **THEN** the one local service updates the shared project metadata and the selected project's derived index

