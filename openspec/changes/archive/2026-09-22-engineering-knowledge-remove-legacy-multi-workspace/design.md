## Context

The single-workspace runtime has passed focused HTTP, WebUI, and MCP verification and is now the documented product. The repository nevertheless retains the old `KnowledgeBaseCli` manifest entry, `KnowledgeBaseHttpApp`, `KnowledgeBaseHttpService`, `WorkspaceRegistry`, `WorkspaceContext`, `/api/v1` client code, legacy tests, and documentation. Some classes near those packages are still reused by `KnowledgeBaseDocumentApplication`, so package location or age alone is not proof that a file is deletable.

This cleanup is intentionally breaking for the old runtime. It must not change the fresh workspace format, supported `/api/v2` contract, MCP schemas, engineering knowledge model, or document-domain correctness requirements.

## Design Readiness

`READY`

Deletion is authorized only after Work Item 0.1 records production-root reachability and class/resource references. A file with an unresolved caller or shared-domain role stays in place and is recorded with a `%%` note rather than guessed away.

## Goals / Non-Goals

**Goals:**

- Remove transport, routing, configuration, tests, assets, and documentation used only by the legacy multi-workspace `/api/v1` runtime.
- Make the supported single-workspace HTTP launcher the packaged service entry and retain local stdio MCP as an explicit main class.
- Preserve every document-domain and engineering-knowledge component reachable from the production single-workspace composition.
- Leave the repository with no active code path that accepts logical workspace registration/selection or opens a legacy layout.
- Delete in small dependency-ordered batches with static reference evidence and compile after implementation batches.

**Non-Goals:**

- Migrating, importing, converting, or deleting user data.
- Changing `/api/v2`, MCP tool schemas, the workspace marker, SQLite schema, Lucene layout, authentication, deployment, or remote transport.
- Broad package renames or aesthetic refactoring of retained domain code.
- Removing a class merely because coverage or current references are uncertain.

## Decisions

### Reachability, not package membership, decides deletion

The inventory starts from `SingleWorkspaceHttpServerMain`, `KnowledgeMcpServerMain`, `SingleWorkspaceLauncher`, `SingleWorkspaceKnowledgeService`, bundled WebUI resources, and Maven packaging. It traces compile-time references, reflective main-class/config references, tests, scripts, and documentation. Each candidate is classified as `DELETE`, `KEEP_SHARED`, or `GATE` with evidence.

Alternative considered: delete the complete legacy `http` and `cli` packages. Rejected because the production document adapter reuses document-domain services and LLM construction that may be located near legacy entry code.

### Delete leaf surfaces before shared internals

Deletion proceeds in reviewable batches: obsolete WebUI/docs/config remnants; old HTTP app/service/registry/context and exclusive DTOs; old CLI HTTP client/commands; then any newly orphaned helpers/tests. After each batch, repository references are rescanned before another class is removed.

Alternative considered: one bulk deletion followed by compiler repair. Rejected because compilation errors do not detect reflective entry points, resource paths, documentation promises, or accidentally weakened test coverage.

### Packaging points to the supported service

The shaded-jar manifest will stop naming `KnowledgeBaseCli` and will name `SingleWorkspaceHttpServerMain`. MCP remains an explicit stdio main class because a jar cannot have two default mains. README examples may then use `java -jar` for HTTP while retaining the explicit MCP class.

Alternative considered: remove the manifest main entirely. Rejected because the project already publishes a shaded executable jar and the HTTP service is the primary interactive entry.

### Preserve reusable domain behavior and tests

`KnowledgeBase`, document/session persistence, ingestion, LLM clients, and other types used by `KnowledgeBaseDocumentApplication` remain even if originally introduced for the legacy CLI/server. Tests are removed only when their production subject is deleted; retained behavior must remain covered by the single-workspace fixtures or replacement focused tests.

The legacy HTTP tests contain retained document and session behavior that is not yet exercised
through `/api/v2`. Work Item 2.2 therefore replaces those assertions with focused v2 boundary
coverage before deleting the old test classes. Existing repository/domain tests remain the
authority for revision inheritance, metadata precedence, trash storage, sessions, and committed
search visibility; the replacement HTTP coverage proves routing and serialization rather than
duplicating all domain cases.

### Replace the legacy configuration object instead of trimming it in place

`KnowledgeBaseConfig` combines LLM settings with the old CLI, workspace registry, WebUI override,
EventBus, and legacy default-file discovery. `SingleWorkspaceLauncher` currently reuses it only as
an `LlmProviderSettings` carrier. Before the old HTTP class is deleted, the launcher will move to
a small immutable single-workspace LLM settings implementation with the same provider defaults.
`KnowledgeBaseConfig`, `TomlConfigLoader`, and their legacy parsing paths can then be deleted
together; `EnvLoader` is deleted only if the orphan sweep confirms no retained caller.

Alternative considered: keep and progressively trim `KnowledgeBaseConfig`. Rejected because it
would preserve a public mutable legacy configuration surface and make obsolete keys appear
supported after their commands are gone.

### Active documentation describes only supported surfaces

The article-editing and performance guides describe retained behavior, so their endpoint examples
will be rewritten for `/api/v2`. The multi-workspace ADR, glossary, compatibility record,
management acceptance checklist, and discussion-stage concurrency proposal describe only the
removed product and will be deleted. OpenSpec archive content remains untouched as the durable
decision history.

### Frozen deletion batches and rollback boundary

The implementation order is fixed as follows:

1. Rewrite or delete active legacy documents/config examples; no Java deletion.
2. Switch the Maven manifest and README launch contract to the supported HTTP main.
3. Add the small retained LLM settings seam and remove the old HTTP/EventBus registry closure.
4. Add focused `/api/v2` document/session boundary coverage, then remove old HTTP tests.
5. Remove the old CLI/client/configuration closure and its tests.
6. Run the orphan/static-contract sweep and remove only newly unreferenced leaves.

Each batch is independently revertible as source changes. The persisted workspace marker,
SQLite/Lucene state, user files, schemas, dependencies, and archived history are outside every
batch, so rollback is code/document restoration only.

### Specs distinguish obsolete routing from retained consistency

The `workspace-service` requirements for CLI discovery, logical workspace status/identity, and registry-based routing are removed. SQLite/Lucene authority, transactionality, retry, rebuild publication, synchronous outcomes, and freshness remain. Per-workspace wording that still describes the one service-owned queue is narrowed instead of discarded.

## Risks / Trade-offs

- **Accidental deletion of shared document behavior** → Require the reachability matrix and rescan after every deletion batch; uncertain files remain gated.
- **Hidden reflection or packaging reference** → Search manifests, scripts, resources, class names, and configuration strings in addition to Java imports.
- **Tests pass only because legacy fixtures disappear** → Keep or add focused tests around each retained application boundary before deleting the corresponding legacy test.
- **Dirty primary workspace obscures ownership** → Limit edits to the new cleanup change and explicitly listed candidates; preserve all prior single-workspace changes.
- **Default jar behavior changes** → Treat the manifest change as deliberate breaking behavior and document the new `java -jar` contract.
- **Rollback after deletion** → Git/code rollback restores removed source; persisted data is untouched. No runtime data rollback or migration is performed.

## Migration Plan

1. Inventory and classify every legacy candidate plus direct/transitive references.
2. Freeze the keep/delete matrix and resolve any `GATE` before deletion.
3. Remove legacy surface batches from leaves inward, updating packaging and docs alongside the responsible batch.
4. Compile after each implementation batch; run one explicitly authorized focused single-workspace suite after all deletions.
5. Confirm no `/api/v1`, logical workspace registration/selection, or legacy main entry remains outside archived specifications/history.
6. Keep Git delivery and OpenSpec archive separate from implementation completion.

Rollback restores the previous executable/source revision. Because this change does not open or mutate legacy data and does not change the new workspace format, no database rollback is required.

## Open Questions

None at design level. Candidate-specific uncertainty is handled as a deletion Gate in the inventory rather than by expanding scope.
