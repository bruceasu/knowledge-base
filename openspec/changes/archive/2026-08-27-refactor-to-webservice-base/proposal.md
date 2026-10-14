# Refactor content access to a multi-workspace web service

## Problem Statement

当前 CLI、HTTP 请求和其他调用路径可能分别创建 `KnowledgeBase`，并各自打开 SQLite 连接和 Lucene `IndexWriter`。当多个线程或进程同时写入同一 workspace 时，会产生资源竞争、索引 writer 生命周期不一致、查询期间提交不一致以及跨 workspace 互相阻塞等问题。

## Solution

引入单进程 Server 作为 workspace 内容资源的唯一所有者。一个 Server 管理多个相互隔离的 workspace；每个 workspace 拥有独立的运行时上下文、SQLite、Lucene 索引和 FIFO 写队列。

CLI 保留 `server start/stop/status` 管理职责，内容相关命令继续保留兼容入口但只通过 API 转发，不再直接操作 workspace 文件、SQLite 或 Lucene。读请求可以并发执行并读取最近一次可用快照；写请求在 workspace 内严格排队。

## User Stories

1. As a CLI user, I want existing sync, search, and ask commands to work through the server, so that content operations do not bypass concurrency control.
2. As a server operator, I want one server to manage multiple workspaces, so that each workspace can share process infrastructure without sharing mutable resources.
3. As a workspace user, I want reads to continue during a sync or rebuild, so that long-running writes do not make the knowledge base unavailable.
4. As a workspace user, I want writes to execute in FIFO order, so that concurrent updates cannot corrupt SQLite or Lucene state.
5. As a workspace user, I want a failed index update to be retryable, so that a temporary Lucene or file error does not require manual data reconstruction.
6. As a workspace user, I want SQLite business state to remain authoritative, so that the search index can be rebuilt without changing the meaning of stored documents.
7. As an operator, I want one broken workspace to be isolated, so that other workspaces remain available.
8. As a user removing a root, I want detach to be the safe default, so that accidental permanent deletion is avoided.
9. As a user performing an explicitly confirmed purge, I want reads to remain available, so that maintenance does not unnecessarily block the workspace.
10. As a developer, I want the existing HTTP boundary to remain the primary integration seam, so that concurrency behavior can be tested through observable API behavior.

## Implementation Decisions

- Use one long-lived server process with multiple workspace contexts.
- Give each workspace one FIFO write queue. Sync, ingest, rebuild, document mutations, root mutations, and purge are write operations.
- Permit reads to run concurrently and observe the most recent available committed snapshot.
- Treat SQLite as the business-data authority and Lucene as a rebuildable search projection.
- Keep the first release synchronous at the public API level. Internal operation records and checkpoints are allowed but are not exposed as `operationId` resources.
- Store operation state in the workspace SQLite where possible; fall back to service logs and in-memory state when SQLite is unavailable.
- After restart, do not automatically resume unfinished operations. Explicit retry through sync or rebuild must use idempotent processing and checkpoints.
- Keep the CLI content command names for compatibility, but make them API-client forwarding commands.
- Keep service access on loopback and retain health/status checks as the discovery mechanism for the first release.
- Make detach the default root removal behavior; require explicit confirmation for purge.

## Testing Decisions

- Prefer the HTTP application boundary as the highest seam.
- Extend existing HTTP application tests to cover multiple workspaces, synchronous CLI/API forwarding, read/write overlap, FIFO write ordering, stale-but-valid reads, and isolated workspace failure.
- Use service-level tests only where HTTP behavior cannot deterministically observe queue ordering, checkpoint persistence, or storage recovery.
- Preserve existing `KnowledgeBaseTest`, incremental sync tests, CLI tests, and HTTP tests as regression coverage.
- Tests must assert external behavior and stable response contracts, not executor or lock implementation details.

## Out of Scope

- Public asynchronous operation APIs and `operationId` polling.
- Distributed or multi-host workspace sharing.
- Concurrent service versions managing the same workspace.
- Cross-storage atomic transactions between SQLite and Lucene.
- Automatic idle shutdown or automatic operation resume after restart.
- Full authentication, authorization, audit history, and document version rollback.
- Final Windows lock recovery policy.

## Further Notes

%% The exact server port allocation and runtime-file protocol remain unresolved.

%% The checkpoint schema is intentionally minimal: business and projection positions at batch granularity. SQLite-unavailable repair is an explicit reopen/rebuild action, not automatic recovery.

%% Retry uses three attempts with default delays of 100 ms, 300 ms, and 900 ms; deployment may override the delays.

%% Latest-write status is workspace-scoped and intentionally does not provide per-operation polling.

%% Purge dry-run is excluded from this change. Security, authentication, authorization, and audit are outside this architecture review and current implementation scope.
