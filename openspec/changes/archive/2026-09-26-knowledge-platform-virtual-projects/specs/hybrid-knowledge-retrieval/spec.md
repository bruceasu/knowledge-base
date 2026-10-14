## ADDED Requirements

### Requirement: Selected virtual project receives deterministic retrieval preference

The hybrid retrieval Module MUST accept an optional virtual-project identifier in a query. When present, it MUST retrieve bounded candidates from the shared knowledge scope and the selected project's index, then apply a deterministic project preference after candidate normalization. It MUST preserve source-internal ordering and provenance, MUST NOT compare raw scores across heterogeneous sources as if they shared one scale, and MUST NOT call an LLM for ordinary retrieval or ranking. Without a project identifier, retrieval MUST retain shared-scope-only behavior.

#### Scenario: Retrieve with a selected project
- **WHEN** a valid query includes an available virtual-project identifier
- **THEN** normalized shared and project candidates are returned in deterministic order with project candidates receiving the specified additional preference

#### Scenario: Project retrieval fails while shared retrieval succeeds
- **WHEN** the selected project's index is unavailable and shared sources return candidates
- **THEN** retrieval returns the shared candidates with an incomplete diagnostic for the project source

#### Scenario: Repeat the same project-aware query
- **WHEN** the same query and unchanged source candidates are retrieved repeatedly
- **THEN** order and project preference are deterministic and no LLM call is made
