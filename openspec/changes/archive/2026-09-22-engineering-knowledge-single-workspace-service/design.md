## Context

The repository contains a mature legacy document service built around `WorkspaceRegistry` and `WorkspaceContext`, plus newly implemented engineering-knowledge core, projection Store, ingestion, retrieval, Context Builder, application facade, and MCP transport modules. The HTTP/WebUI startup still selects the legacy multi-workspace composition, while `KnowledgeMcpServerMain` deliberately starts with an unavailable placeholder handler.

This version is a new implementation. It does not need to read, migrate, or preserve legacy workspace data or configuration. SQLite and Lucene remain local process-owned resources; the service is not a multi-node authority and must not place SQLite/WAL/SHM on a shared network filesystem.

## Design Readiness

`FD_APPLIED`

The product boundary is decided: one process owns exactly one configured workspace, old persisted content is unsupported, and the new engineering-knowledge application composition is authoritative. Physical deletion of legacy packages remains deferred until the replacement has passed end-to-end verification.

## Goals / Non-Goals

**Goals:**

- Provide one production composition root shared by HTTP and local stdio MCP.
- Own one workspace root and one lifecycle for document state, engineering projections, SQLite, Lucene, external-tool profiles, and application modules.
- Expose the supported ingestion, retrieval, controlled read, trace, Context Builder, search/ask, document/session, status, and maintenance operations without a workspace selector.
- Simplify the bundled UI while preserving its useful single-workspace workflows.
- Fail startup or readiness explicitly when the configured workspace or required composition cannot be opened.

**Non-Goals:**

- Migrating legacy databases, Lucene indexes, configuration, sessions, or content.
- Preserving multi-workspace API/configuration compatibility.
- Remote MCP, authentication, multi-instance writes, server-side database migration, or shared-filesystem SQLite.
- Deleting legacy multi-workspace code before replacement verification.

## Decisions

### One immutable runtime workspace

The new startup accepts one workspace root, normalizes it once, and constructs an immutable workspace descriptor/catalog containing the single internal identity required by existing engineering interfaces. Callers never choose or register a workspace, and transport payloads do not expose `workspaceId`.

Internally retaining a constant identity avoids weakening the already implemented repository-confinement and evidence contracts. It is an implementation detail, not a second product workspace concept.

Alternative considered: retain `WorkspaceRegistry` with one entry. Rejected as the production composition because it preserves unnecessary registration, switching, lifecycle, and error states.

### One application composition root

A new composition root owns resource construction and close order. It wires the concrete knowledge Store, document/code/Git adapters, projection ingestion, hybrid retriever, controlled read, relation trace, Context Builder, workspace catalog, and `DefaultKnowledgeAgentFacade`. HTTP handlers and MCP handlers receive application interfaces from this root; neither transport constructs or accesses SQLite, Lucene, Git, ripgrep, or Ctags directly.

The detailed graph and ownership contract are fixed in `composition-design.md`. A retrieval runtime factory owns Lucene and exposes only retrieval/recovery/status seams; a Context Builder factory adapts the package-private relation reader; and one application service exposes document plus engineering operations to HTTP and the agent facade to MCP. The fresh document module may reuse existing implementation internally but must live under the new layout and must not reuse `WorkspaceRegistry` or legacy transport ownership.

The root publishes readiness only after SQLite migration/open, Lucene open/recovery, and required application wiring succeed. Close first rejects new work, stops HTTP and MCP acceptance, drains or interrupts bounded accepted work, then closes the document module, retrieval/Lucene runtime, and finally the engineering Store. Partial construction unwinds in reverse order and close remains idempotent.

### Fresh-state-only startup

The new launchers require exactly one `--workspace-root` CLI value or `workspace_root` TOML value, with no implicit default. The workspace uses `.engineering-knowledge-workspace.json` containing format `engineering-knowledge-single-workspace` and integer version `1`. Startup initializes only an absent or empty root, publishing the marker atomically before opening mutable resources, or opens a compatible marked layout. A non-empty unmarked root, malformed marker, another format, or incompatible version fails without opening or mutating other state.

Rollback is executable-code rollback plus restoration of a separately backed-up new-format workspace. No down-migration is promised.

### New HTTP contract and simplified UI

The new HTTP surface is mounted only under `/api/v2`; it does not alias or redirect `/api/v1`. It removes workspace registration/repair/unregistration routes and ignores no hidden fallback workspace. Workspace-scoped operations become service-scoped operations and reject obsolete workspace routing in query, JSON, multipart, or recognized headers with HTTP 400 and code `OBSOLETE_WORKSPACE_FIELD`.

`/api/v2/health` reports process liveness. `/api/v2/ready` returns 200 only for a fully accepting composition and 503 otherwise, with stable schema version, state, component states, queue depth, and freshness fields. `/api/v2/status` carries fuller diagnostics. The unauthenticated readiness payload does not disclose the absolute workspace root.

The bundled UI removes workspace selection, registration, lifecycle lists, and cross-workspace fallback. It continues to use status, ingestion, search/ask, document/session, root/index, and maintenance operations supported by the new HTTP contract. Source references need only stable object/document identity plus workspace-relative location.

### Production MCP composition

`KnowledgeMcpServerMain` loads the same configuration and composition root as HTTP, then registers all six existing read-only tools through `KnowledgeAgentFacade`. The MCP protocol, schema version, bounded requests/responses, deadline/thread-interruption behavior, stderr-only logging, and no-network-listener constraints remain unchanged. SDK 2.0.1 protocol cancellation remains best-effort and is not advertised as guaranteed.

### Production document and launcher factories

The transport-independent `SingleWorkspaceDocumentApplication` is implemented by a production adapter over the existing `KnowledgeBase`, opened only at `<workspace-root>/document`. The adapter owns that `KnowledgeBase` instance, translates every declared document/session operation to its typed service methods, validates transport-neutral argument values, and returns the existing application result values. It does not reuse `WorkspaceRegistry`, `WorkspaceContext`, legacy HTTP handlers, or a legacy workspace root. Unsupported or malformed operation arguments fail explicitly; no operation may silently succeed as a placeholder.

A shared launcher factory loads the new service configuration once and opens the production composition for HTTP-only, MCP-only, or combined startup. CLI values override the corresponding TOML values, while obsolete keys remain fatal even when a canonical value is also present. LLM settings are converted through the existing client factory; the document adapter receives the resulting client without exposing provider details to transports. Ripgrep, Universal Ctags, and Git profiles use immutable bounded settings and require configured absolute executable paths. An absent optional tool is represented by an explicit unavailable profile/diagnostic rather than an unbounded `PATH` lookup. The factory performs no transport registration until the complete application composition is ready and closes partially constructed resources in reverse order.

This factory is the only production caller of `SingleWorkspaceKnowledgeService.open`. HTTP and MCP entry points select transport settings, obtain the same application composition contract, and do not duplicate configuration parsing or resource wiring.

### Legacy code removal is delayed

Legacy multi-workspace classes and UI sources may be bypassed or minimally adapted in this change, but broad physical deletion occurs only in a later cleanup change after the new HTTP/UI/MCP smoke suite passes. This keeps failures attributable to composition work rather than simultaneous deletion.

## Risks / Trade-offs

- [Breaking configuration and API] → Use a distinct startup/config contract and reject obsolete fields with clear diagnostics.
- [Accidental legacy data opening] → Require the new-format marker before opening existing state; never mutate unrecognized layouts.
- [Duplicated HTTP and MCP resource graphs] → Both transports must obtain interfaces from one composition root and share one close lifecycle.
- [External tools unavailable] → Report bounded `UNAVAILABLE`/incomplete diagnostics while keeping document-only operations usable where their dependencies are healthy.
- [UI accidentally retains workspace state] → Remove shared workspace-selection storage and add static plus browser-level request assertions for absence of `workspaceId` and fallback loops.
- [Large blast radius] → Implement composition and transport conversion in reviewable Work Items; defer package deletion.

## Migration Plan

1. Add the new configuration and fresh workspace marker without changing the current launcher.
2. Build and verify the single-workspace composition root and application-level smoke fixture.
3. Add the production document adapter and shared launcher factory, then switch the new HTTP entry point and bundled UI to the new contract.
4. Wire the stdio MCP entry point through that same launcher factory and production composition.
5. Run authorized HTTP/UI/MCP smoke checks against a temporary empty workspace.
6. Make the new entry point the documented launcher. Roll back by restoring the previous executable; new-format data is not opened by the legacy implementation.

## Open Questions

- Whether legacy source files are physically deleted is intentionally deferred to a separate cleanup decision after replacement verification.

The HTTP version, canonical configuration keys, marker/version, readiness payload, and obsolete-field behavior are fixed by `contract-decisions.md`.

The production graph, factory Gates, resource ownership, and close order are fixed by `composition-design.md`.
