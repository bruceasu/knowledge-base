# engineering-context-builder Specification

## Purpose
TBD - created by archiving change engineering-knowledge-context-builder. Update Purpose after archive.
## Requirements
### Requirement: Single bounded context-building Interface

The system SHALL expose context assembly through one `build(ContextRequest) -> EngineeringContext` Interface. A request MUST contain a repository-confirmed workspace, target description, controlled intent, and positive token budget at or above the configured minimum; it MUST NOT require retrieval, graph, database, Lucene, or external-tool parameters.

#### Scenario: Build context for a valid target

- **WHEN** a caller supplies a valid workspace, target description, supported intent, and sufficient token budget
- **THEN** the Module returns one `EngineeringContext` with ordered items, total estimated tokens, estimator version, diagnostics, and completeness state

#### Scenario: Reject a budget below the minimum target header

- **WHEN** a caller supplies a non-positive or insufficient token budget
- **THEN** the Module returns a deterministic invalid-request diagnostic before retrieval or relation expansion begins

### Requirement: Use only current traceable targets and evidence-bearing relations

The Builder MUST locate targets through `KnowledgeRetriever` and expand only a fixed one hop over confirmed evidence-bearing relations through a controlled relation-read seam. It MUST prefer current canonical objects, then current derived objects; stale/retryable/failed projections MUST be excluded by default and may be included only when explicitly allowed and clearly marked.

#### Scenario: Include a direct verified design relation

- **WHEN** a current target has a direct relation to a current design object and the relation has valid evidence
- **THEN** the context includes the design object with the relation/evidence reason subject to ordering and budget

#### Scenario: Encounter an evidence-less or stale related object

- **WHEN** a candidate relation lacks evidence or its related object is stale while a current alternative exists
- **THEN** the Builder excludes it from the default context and records an applicable diagnostic or omission reason rather than presenting it as current support

### Requirement: Deterministic priority, deduplication, and budget packing

The Builder SHALL select candidates using the documented fixed priority: target, directly evidenced requirement/design/decision, direct current code symbol, corresponding test, matching Git evidence, then other current related items. It MUST deduplicate only by stable object identity or stable source location, retain merged evidence, use stable tie breakers, and apply the versioned deterministic token estimator before packing items.

#### Scenario: Repeat an unchanged context request

- **WHEN** the same fixed knowledge graph and request are built twice with the same estimator version
- **THEN** the returned item order, item selection, per-item estimates, total estimate, and omission records are equivalent

#### Scenario: Prefer an exact target over a lower-priority current item

- **WHEN** the budget can fit the target and only one of several lower-priority related items
- **THEN** the target is included first and lower-priority selection follows the documented priority and stable tie-break order

### Requirement: Explicit truncation and omissions without generated summaries

When a complete item does not fit the remaining budget, the Builder MUST either include a deterministic source-located fragment with `truncated` status or omit the item. It MUST return an omission record for every excluded/merged/stale/failed-budget candidate, including a reason and identity when available. It MUST NOT invoke an LLM or fabricate a summary to fit the budget.

#### Scenario: Truncate a high-priority item at the budget boundary

- **WHEN** a selected high-priority item exceeds remaining budget but its minimum source header fits
- **THEN** the context includes a deterministic bounded fragment with original source range and truncation flag, and reports omitted remainder information

#### Scenario: Omit a lower-priority item because of budget

- **WHEN** a lower-priority current item cannot fit after higher-priority items are packed
- **THEN** the context leaves it out and includes an omission record identifying the budget reason rather than silently dropping it

### Requirement: Per-item provenance and transparent incompleteness

Every context item MUST carry stable object/source identity when available, canonical/derived/freshness state, contributing source/evidence, confidence, and selection reason. The context MUST preserve retrieval/relation diagnostics and mark itself incomplete when a required source or relation read fails, while returning any available valid items.

#### Scenario: Build context with a failed optional retrieval source

- **WHEN** the target and some current evidence are available but an optional retrieval source fails
- **THEN** the context returns available traceable items, includes the source diagnostic, and marks the context incomplete rather than inventing missing content

### Requirement: Keep Context Builder isolated from infrastructure and existing endpoints

The Builder MUST consume application-level retrieval and relation-read behavior only. It MUST NOT directly call SQLite, Lucene, ripgrep, Ctags, Git, LLMs, or external token services; it MUST NOT write knowledge data or modify existing `KnowledgeBase`, `KnowledgeRepository`, `LuceneSearchIndex`, HTTP/CLI, MCP, dependencies, or deployment configuration.

#### Scenario: Exercise context building with a fixed knowledge fixture

- **WHEN** a focused test invokes `build` with fixed retrieval and relation data
- **THEN** it verifies context items and budget behavior without a database query, external tool, LLM, HTTP server, CLI route, or MCP transport call

