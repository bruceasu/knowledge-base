# knowledge-base 产品介绍与答疑手册

本文用于向同事、使用者、评审者介绍 `knowledge-base`，也可作为常见问题答疑稿。操作步骤请配合[用户与部署手册](user-guide.zh.md)使用。

## 1. 一句话介绍

`knowledge-base` 是一个运行在本地、面向单个工程 workspace 的知识服务：它把文档和代码相关信息转成可搜索、可追溯、可组装上下文的知识对象，并通过 WebUI、`/api/v2` 和个人秘书 HTTP API提供给人和 AI 助手。

## 2. 它解决什么问题

常见工程信息分散在说明文档、设计、需求、代码、测试和提交记录中。单纯全文搜索通常只能找到字符串，难以回答“这个结论来自哪里”“相关设计和代码是什么”“信息是否仍然新鲜”。

本服务提供：

- 统一摄取文档，并保存来源与 provenance；
- SQLite 保存权威业务状态，Lucene 提供可重建的搜索 projection；
- BM25 与结构化候选组合检索；
- 知识对象读取、关系 trace 和有 token budget 的 Context Builder；
- WebUI 给人使用，HTTP API 给自动化使用，秘书 HTTP API 给个人助手使用；
- 单 workspace、单进程所有权，减少多租户路由和并发资源所有权的复杂度。

## 3. 适合与不适合的场景

适合：

- 个人或团队在受控主机上维护一个工程知识空间；
- 需要让 AI 助手检索有来源、可追溯的工程上下文；
- 需要文档编辑、revision、metadata、session 和问答能力；
- 希望数据、索引和工具调用都留在本地进程边界内。

不适合或当前未提供：

- 多租户 SaaS、逻辑 workspace 动态切换；
- 多实例同时读写一个 workspace；
- 内建 TLS、集群；
- 直接打开旧 `/api/v1` 多工作区数据；
- 把共享网络文件系统当作 SQLite/Lucene 存储。

## 4. 对外介绍时可以这样讲

### 30 秒版本

> 这是一个本地工程知识服务。它把文档和工程信息保存成带来源的知识对象，用 SQLite 保证业务状态，用 Lucene 提供搜索，再通过网页、HTTP 给人或 AI 使用。它的重点不是“再做一个聊天框”，而是让检索结果、关系和上下文有来源、有新鲜度状态，并且能在信息变化后重新同步和恢复。

### 3 分钟版本

1. 一个 Java 进程拥有一个 workspace，数据不会在多个逻辑 workspace 之间路由。
2. 用户导入文件或同步目录后，服务解析内容、metadata 和结构，提交 SQLite 业务状态，再更新搜索 projection。
3. 搜索、对象读取、trace 和 Context Builder 使用已经提交的状态，并返回 provenance/evidence。
4. 人可以使用 WebUI，系统可以调用 `/api/v2`，个人秘书可以调用版本化 HTTP API。
5. 当源文件变化时再次 sync：未变化文件按 hash 跳过，变化文件生成新状态和 projection，已删除文件从当前状态移除。
6. 如果 SQLite 已更新但索引发布失败，服务不会假装完全成功；status/readiness 会暴露 projection lag，之后可显式 sync 或 rebuild。

## 5. 核心结构

```text
WebUI / HTTP / 秘书 API
            │
            ▼
  单工作区应用与操作协调
       ├── 文档、revision、session
       ├── SQLite 权威业务状态
       ├── 工程知识 projection
       ├── Lucene 检索快照
       ├── trace / Context Builder
       └── 受限 ripgrep / Ctags / Git adapter
```

HTTP transport 不直接创建 Store、索引或外部工具；这些资源由共同的 application composition 管理。

## 6. 用户最关心：信息更新时怎么办

### 6.1 更新入口

| 信息变化 | 推荐操作 | 结果 |
| --- | --- | --- |
| 目录中新建或修改文件 | 再次执行目录 sync（管理页或 `POST /api/v2/ingest/dir`） | 变化内容进入新业务状态并重新 projection |
| 只更新一个文件 | 执行文件 ingest（`POST /api/v2/ingest/file`） | 只处理目标文件 |
| 在 WebUI 编辑正文 | 保存文档 | 创建新 revision，保留 Article 身份与人工 metadata |
| 在 WebUI 修改 title/tags/keywords/summary | 保存 metadata | 不创建内容 revision，但重新 projection 当前文档 |
| 源目录中删除文件 | 对该目录再次 sync | 当前文档状态与搜索 projection 移除该路径 |
| 搜索索引损坏或长期落后 | 显式执行 rebuild | 从 SQLite 权威状态重建 Lucene |
| 可选工具或 LLM 配置变化 | 修改配置并正常重启 | 新进程使用新配置；不要并行打开同一 workspace |

### 6.2 更新流程

```text
检测文件/编辑请求
       │
       ├─ 内容 hash 未变化 ──► 跳过重复解码、分块和模型调用
       │                         └─ 但仍恢复未完成 projection
       │
       └─ 内容发生变化
             ▼
      准备文本、metadata、chunks
             ▼
      SQLite 原子提交完整业务单元
             ▼
      记录 business checkpoint 与 pending projection
             ▼
      构建并提交 Lucene projection
             ▼
      推进 projection checkpoint，后续搜索看到新快照
```

关键点：

- SQLite 是权威状态；Lucene 是可重建 projection。
- 外部模型调用和准备工作在数据库事务之外，避免长事务。
- 一个文档的 hash、chunks、metadata、business checkpoint 和 pending projection 作为完整业务单元提交。
- 搜索只读取已提交快照，不读取半成品索引。

### 6.3 更新何时可见

- 成功完成 sync/ingest/save 并发布 projection 后，后续搜索使用新快照。
- 已经开始的搜索可以安全完成，它可能仍使用请求开始时持有的上一个已提交快照。
- 文档详情等 SQLite 读取可能先看到新业务状态；如果 projection 尚未发布，搜索仍可能看到旧的已提交快照。
- 服务用 `businessCheckpoint` 与 `projectionCheckpoint` 表示两者进度；前者领先时属于 projection lag。

### 6.4 更新失败怎么办

先看：

```text
GET /api/v2/health
GET /api/v2/ready
GET /api/v2/status
```

处理原则：

1. `/health` 正常但 `/ready` 为 503：进程活着，但完整服务不可用；查看 status 的 component、reason 和 freshness。
2. SQLite 事务失败：旧的已提交业务状态仍可读；修复输入/权限后重新 sync。
3. SQLite 成功但 Lucene 失败：business checkpoint 会领先 projection checkpoint，服务应报告 degraded；重新 sync 可恢复 pending batch，必要时 rebuild。
4. HTTP 请求超时：超时不等于操作已取消或失败；先查询 status，再决定是否重试。
5. WebUI 保存遇到外部文件改动：服务返回 content conflict，不会静默覆盖；重新加载最新内容后再编辑。
6. rebuild 失败：旧的完整搜索快照应保持可用；修复原因后再次 rebuild。

### 6.5 会不会重复处理所有文件

不会。增量 sync 以内容 hash 和已提交状态判断：完全未变化的文件跳过解码、分块和模型调用。即使内容未变化，只要存在未完成 projection，sync 仍会继续恢复它。

### 6.6 人工修改会不会被下次同步覆盖

metadata 有优先级：人工值高于源文件提取值，源文件值高于 AI 生成值。后续 sync 或 AI 生成不能删除有效的人工 metadata。正文变化会创建新 revision，Article 身份保持稳定。

## 7. 一致性与新鲜度怎么解释

可以用一句话说明：业务状态强一致提交，搜索 projection 最终赶上，但任何时刻只发布完整快照。

- SQLite transaction 保证读者不会看到文档替换的中间状态。
- Lucene 失败不会回滚已经安全提交的源文件/SQLite 状态，也不会发布半成品索引。
- `freshness`、business/projection checkpoint 和最后成功 projection 时间用于解释“为什么搜索可能落后”。
- trace/context/search 返回的来源和 freshness 用于判断信息可信度，而不是假装所有数据永远最新。

## 8. 常见问题

### Q：是否会自动监视目录变化？

当前说明的稳定入口是显式文件 ingest 或目录 sync。不要假设存在后台文件监听；源文件变化后应再次发起 sync。

### Q：信息更新后需要重启吗？

内容和 metadata 更新不需要重启，使用 ingest/sync/WebUI 保存即可。配置、端口、LLM provider 或外部工具路径变化需要正常重启进程。

### Q：每次都需要 rebuild 吗？

不需要。正常 ingest/sync/save 会增量更新 projection。只有索引需要从权威 SQLite 全量恢复，或长期 projection 问题无法通过显式重试解决时才 rebuild。

### Q：删除源文件后搜索结果会立刻消失吗？

对所属 root 再次 sync 后，服务检测缺失路径并更新业务状态与 projection。进行中的搜索可能完成于旧快照，后续搜索使用新提交快照。

### Q：能否两台机器共享一个 workspace？

不能。SQLite/WAL/SHM、Lucene 和进程所有权要求主机本地文件系统，并且一个目录同时只能由一个进程打开。

### Q：是否还需要 MCP？

不需要。MCP 入口已移除，个人秘书通过 HTTP API 接入，知识库仍由一个独立进程持有 workspace。

### Q：数据会发送到外部吗？

SQLite/Lucene/workspace 保存在本地。若配置远程 LLM provider，则相关模型请求会发往所配置 endpoint；若启用外部工具，则会启动明确配置的本地可执行文件。部署方应按自己的数据策略选择 provider 和工具。

### Q：服务是否有权限管理？

个人资料模式提供秘书凭证及集合授权，浏览器使用 Caddy + Authelia，并由应用校验可信代理与写请求来源。后端只监听 loopback，实际远程部署仍需验证入口隔离。

### Q：如何知道系统是否真的更新完成？

查看更新请求结果以及 `/api/v2/status`。当服务 ready、没有 pending projection、且 business/projection checkpoint 一致时，表示业务状态与搜索 projection 已赶齐。

### Q：备份时能否只复制数据库？

不能。停止服务后复制完整 workspace，包括 marker、document、state、SQLite WAL/SHM 和 index。恢复也以完整 workspace 为单位。

### Q：旧 `/api/v1` 或旧 workspace 数据怎么办？

当前版本不打开、不迁移旧布局，也没有兼容路由。应使用新的空 workspace 重新摄取受支持的源内容。

## 9. 演示建议

一次 10 分钟介绍可以按以下顺序：

1. 展示 `/health`、`/ready`、`/status`，说明“活着”和“数据可用”不同。
2. 在管理页导入一个小目录并执行搜索。
3. 修改一个文件，再次 sync，展示搜索结果更新。
4. 在 WebUI 修改 metadata，说明人工值优先并触发 reproject。
5. 展示知识对象来源、trace 或 Context Builder。
6. 展示秘书 API 的权限边界、引用和幂等保存。
7. 最后说明单 workspace、安全边界、备份单位与更新失败恢复方法。

## 10. 相关文档

- [用户与部署手册](user-guide.zh.md)
- [单工作区服务运行说明](single-workspace-service.md)
- [文章编辑说明](article-editing.md)
- [配置示例](../config-example.toml)
