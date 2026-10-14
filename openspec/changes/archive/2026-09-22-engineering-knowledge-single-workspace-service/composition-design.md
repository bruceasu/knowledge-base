# Production Composition and Lifecycle

This document is the evidence for Work Item 0.3. It defines the graph that Work Items 1.3, 1.4, 2.1, and 4.1 must implement. Concrete class names below are design names; implementations may use nearby package naming while preserving the ownership boundaries.

## Composition boundary

One `SingleWorkspaceKnowledgeService` is the sole production `AutoCloseable` composition root. A process creates at most one instance. HTTP and MCP receive only the root's application interfaces and lifecycle/status view; they never receive filesystem paths, SQLite connections, Lucene objects, process launchers, or concrete Store references.

The internal workspace ID is the fixed value `service`. The singleton root map/catalog is created once and shared with confinement-aware engineering adapters. It is not exposed through transport contracts.

## Construction graph

Construction proceeds in this order and aborts by closing already-created resources in reverse order:

1. Parse and validate `SingleWorkspaceServiceConfig`; normalize the one root and reject obsolete fields.
2. Validate or atomically initialize the version-1 workspace marker before opening mutable resources.
3. Create the immutable workspace descriptor, singleton catalog/map, and fixed local-tool profiles.
4. Open the fresh-layout document module in a dedicated new-format subdirectory. It may reuse existing document/session implementation internally, but it must not use `WorkspaceRegistry`, open a legacy root layout, or escape the composition lifecycle.
5. Open one `SqliteKnowledgeStore` for engineering objects and apply its owned additive migrations.
6. Open one retrieval runtime, which owns the engineering Lucene directory/writer/reader and builds the structured, BM25, text, symbol, and Git candidate sources over the shared Store/adapters.
7. Construct the ripgrep, Ctags, and read-only Git adapters from the singleton root map and immutable bounded profiles. These adapters spawn and terminate subprocesses per call; they do not own a permanent process.
8. Construct `SqliteKnowledgeProjectionIngestion`, `SqliteKnowledgeObjectReader`, and `SqliteKnowledgeRelationTracer` over the same Store and internal identity.
9. Construct the Context Builder through its public factory, adapting the relation tracer inside the context package.
10. Construct `DefaultKnowledgeAgentFacade` and the application service that coordinates document operations, engineering projection ingestion, retrieval, status, and maintenance.
11. Create the bounded operation coordinator/executor. Publish readiness only after Store open/migration, Lucene open/recovery, application wiring, and an initial consistency check succeed.
12. Start enabled transports with application interfaces from this already-ready root. Transport construction is outside the resource graph consumed by application modules.

HTTP-only and MCP-only processes use the same root factory and graph. If both transports are enabled in one process, they share the same root instance; neither calls the factory independently.

## Required public factory seams

The current package-private constructors are intentional implementation boundaries. Work Item 1.3 must add these narrow public seams instead of widening candidate-source classes:

- A retrieval-package factory opens a `RetrievalRuntime extends AutoCloseable`. It accepts the internal workspace descriptor/catalog, shared `SqliteKnowledgeStore`, code/symbol/Git application adapters, index path, and bounded settings. It exposes only `KnowledgeRetriever`, projection recovery/rebuild operations needed by the application layer, and status. It exclusively owns `EngineeringKnowledgeLuceneProjection`.
- A context-package factory returns `ContextBuilder` from `KnowledgeRetriever` and `KnowledgeRelationTracer`. It owns the package-private conversion to `ContextRelationReader`; callers do not implement or import that internal interface.
- A single application-facing service interface groups the operations required by HTTP while exposing `KnowledgeAgentFacade` for MCP. Its implementation coordinates document commits with projection ingestion and does not expose concrete persistence/index classes.

Resolved by Work Item 1.3: `RetrievalRuntimeFactory` and `ContextBuilderFactory` provide the required public seams, and Work Item 5.2 confirmed that HTTP and MCP do not instantiate package internals.

`SingleWorkspaceDocumentApplication` is the transport-independent operation boundary, but the interface
alone does not resolve production composition. Work Item 1.5 supplies a production adapter over one
`KnowledgeBase` opened at the fresh `<workspace-root>/document` subdirectory and a shared launcher factory.
HTTP and MCP receive `SingleWorkspaceApplication`; neither receives `KnowledgeBase`, `WorkspaceRegistry`,
`WorkspaceContext`, document persistence objects, LLM provider details, or external-tool settings. A
close-only module cannot make the production composition ready.

## Launcher configuration boundary

One shared launcher factory owns parsing and construction inputs for every production transport:

1. Load the explicitly selected TOML file, if any, into a flat validated launcher value map without applying legacy workspace defaults.
2. Overlay canonical CLI values on matching TOML values, while rejecting every obsolete CLI or TOML workspace key before filesystem mutation.
3. Build `SingleWorkspaceServiceConfig`, the existing LLM client configuration, and immutable bounded ripgrep, Ctags, and Git profiles.
4. Require external-tool executables to be explicit absolute paths. Missing optional tools become unavailable capabilities with bounded diagnostics; launchers do not search an ambient `PATH` or fabricate executable locations.
5. Open one `SingleWorkspaceKnowledgeService` using the production document-module factory. Return the ready composition to the selected transport only after construction succeeds.

The document adapter maps all operations declared by `SingleWorkspaceDocumentApplication.Operation` to
typed `KnowledgeBase` methods. It owns and closes exactly one `KnowledgeBase`, rejects missing, malformed,
or root-escaping path arguments (including absolute paths outside the service root), and preserves existing document/session return
semantics. The adapter contains no HTTP or MCP request parsing and does not expose the legacy workspace
registry model.

Retained non-blocking retrieval limitation: the read-only Git Adapter requires explicit full commit IDs, while `KnowledgeQuery` contains no commit selection. The composition owns and exposes the Adapter to projection ingestion but does not fabricate a Git retrieval selection; adding query-time Git enrichment requires a separately specified application input or Store-backed commit selection.

## Resource ownership

| Resource | Sole owner | Notes |
| --- | --- | --- |
| Format marker and normalized root | composition root | Validated before every mutable resource. |
| Document database/index/LLM client | document module owned by composition root | Fresh-layout subdirectory only; no legacy import. |
| Engineering SQLite connection and migrations | `SqliteKnowledgeStore`, owned by composition root | One Store instance shared by ingestion, read, trace, and retrieval. |
| Engineering Lucene directory/analyzer/writer/readers | retrieval runtime | Closed before Store; rebuilt from committed Store state. |
| ripgrep/Ctags/Git profiles | composition root as immutable values | Missing executables degrade dependent calls and appear in status; they do not prevent document-only readiness. |
| LLM client and fresh document database/index | production document adapter | Created by the shared launcher factory; confined to `<workspace-root>/document` and closed exactly once by the composition root. |
| External subprocesses and per-call reader executors | respective adapters/calls | Must terminate on success, timeout, interruption, or close initiation; no process pool is shared with transports. |
| Accepted-operation queue/executor | application operation coordinator | The only service-level work executor and shutdown admission gate. |
| HTTP listener/event bridge | HTTP transport | Receives application interfaces; cannot own application resources. |
| MCP SDK stdio server | MCP transport | Receives `KnowledgeAgentFacade`; SDK bounded-elastic internals are not treated as application-owned executors. |

SQLite databases and their WAL/SHM files remain on the configured host-local filesystem. No connection, writer, reader, executor, or subprocess handle is static or shared across composition-root instances.

## Shutdown order

Close is idempotent and follows this order:

1. Atomically change lifecycle to `closing`; readiness becomes false and the application admission gate rejects new operations.
2. Stop HTTP acceptance and event polling, then close the MCP stdio server when present. A standalone launcher performs the applicable subset in the same relative order.
3. Ask the operation coordinator to finish already accepted work within the configured shutdown deadline; after the deadline, cancel futures and interrupt workers. Preserve interrupt status and bounded diagnostics.
4. Confirm adapter-owned subprocesses are terminated by their interrupted calls; adapters have no persistent process to close.
5. Close the document module after accepted projection-producing operations have ended.
6. Close the retrieval runtime and therefore committed Lucene readers, writer, directory, and analyzer.
7. Close `SqliteKnowledgeStore` last among application persistence resources.
8. Mark lifecycle `closed`. Aggregate later close failures as suppressed exceptions while continuing the sequence.

The application operation coordinator uses at most four daemon workers, a FIFO queue of 128
accepted operations, and a ten-second shutdown grace. Each submission also supplies its own
positive deadline; timeout or caller interruption cancels and interrupts that operation. Queue
overflow and all submissions after close initiation fail immediately.

Initialization failure uses the same reverse dependency order for resources that were actually created. A failed or partially closed root never returns to `ready` and every owned resource is closed at most once.

## Readiness and degradation

Required readiness components are `store`, `index`, and `application`. The document module and initial projection/index consistency check are part of `application`. A failure in any required component makes `/api/v2/ready` return 503.

External tools are optional capabilities: unavailable ripgrep, Ctags, or Git produces bounded incomplete/unavailable diagnostics for dependent operations and is reported by `/api/v2/status`, but does not prevent document-only readiness. A later loss of Store/index/application usability moves the root to `degraded` and closes admission for operations whose consistency cannot be guaranteed.
