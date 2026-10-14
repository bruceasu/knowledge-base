## Work Item 5.4 cutover completion

The single-workspace service change is development-complete as of 2026-09-22.
This conclusion combines the static boundary matrix in
`cutover-static-review.md` with the authorized 42/42 focused verification in
`cutover-verification.md`.

### Completed product boundary

- One required canonical workspace root is normalized once and accepted only
  when absent/empty or marked with the exact new format/version.
- The documented HTTP `/api/v2`, bundled WebUI, and local stdio MCP paths use
  the production single-workspace application composition and expose no client
  workspace registration, selection, fallback, or serialized `workspaceId`.
- Store, Lucene/retrieval, document state, projection ingestion, tool profiles,
  readiness, and shutdown stay owned below the transport layer.
- Verification used temporary fresh roots. It did not import, open, migrate,
  delete, or otherwise modify legacy state or repository user data.

### Closed early notes

- The HTTP path/version, canonical configuration keys, marker, readiness
  payload, and obsolete-field behavior were fixed before launcher changes.
- `RetrievalRuntimeFactory` and `ContextBuilderFactory` now provide the missing
  public construction seams; neither transport constructs package internals.
- `SingleWorkspaceLayout.open` runs before Store, Lucene, document, or external
  tool resources are constructed, closing the pre-mutation marker Gate.
- Query-time Git enrichment remains deliberately absent because the stable
  query contract has no full commit-ID selector. Git evidence remains available
  to projection ingestion. Adding query-time selection would require a separate
  specified application contract and is not a cutover blocker.

### Optional legacy cleanup — separate change only

Physical removal is now eligible for consideration because replacement flows
passed, but it is not part of this change and no cleanup Task/change has been
created. If explicitly approved, a minimal follow-up such as
`engineering-knowledge-remove-legacy-multi-workspace` should first inventory
remaining consumers and then independently address:

- `KnowledgeBaseHttpApp`, `KnowledgeBaseHttpService`, `WorkspaceRegistry`, and
  `WorkspaceContext` plus their legacy HTTP tests;
- the `/api/v1` `KnowledgeBaseHttpClient`, old CLI/default-jar entry behavior,
  and any scripts that still invoke them;
- the legacy portions of README documentation and obsolete multi-workspace
  configuration examples;
- proof that no retained document-domain implementation depends on transport
  ownership classes before deleting shared-looking code.

That follow-up must preserve the new launchers, fresh-layout rejection,
rollback documentation, and focused smoke coverage. It must not turn cleanup
into a legacy data migration or silently change the executable entry contract.

### Residual operational limits

- SQLite/WAL/SHM and Lucene are host-local, single-process resources. Separate
  HTTP and MCP processes must not open the same workspace directory.
- Optional native tools were verified through bounded unavailable/degraded
  behavior, not successful native execution.
- MCP SDK 2.0.1 protocol cancellation is best-effort; deadline and thread
  interruption are the enforced first-version contract.
- No remote MCP, authentication, multi-node deployment, down-migration, or
  legacy import is provided.

Development completion is separate from Git delivery, AIW/OpenSpec lifecycle
synchronization, and archive. Those operations require an explicit request.
