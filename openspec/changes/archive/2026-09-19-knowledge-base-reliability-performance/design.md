## Context

沿用已接受的 workspace 并发控制 ADR 与文章编辑/版本 ADR：每个 workspace 独立 FIFO 写队列，SQLite 为业务权威，Lucene 为可重建投影；Article 身份、current revision、手工元数据和回收站语义保持稳定。

静态分析发现：摄取先提交 hash 和删除旧 chunks，随后才生成摘要并写新 chunks；投影批次登记在最后。rebuild 的 clear 立即提交空索引。注册表在全局锁内执行资源打开和关闭，关闭等待超时仍释放资源。搜索每次打开 reader，投影每个 chunk 重查有效元数据，sync 在比较 hash 前解码全文，列表一次加载全部文档。

## Goals / Non-Goals

目标是消除上述失败窗口和工作区间阻塞，在兼容既有契约的前提下减少重复工作。非目标见 proposal；不改变文件系统、SQLite 和 Lucene 不能组成一个原子事务的既定边界。

## Decisions

### 1. 文档准备、业务提交、投影分阶段

在数据库事务之外读取一致的输入字节、计算 hash、解码、分块和调用 LLM。保留现有 AI 失败回退与手工元数据优先级。输入 hash 和内容必须来自同一次读取的字节，防止文件外部修改导致二者不匹配。

在 FIFO 内以短 SQLite 事务替换文档、chunks、相关自动元数据，并登记对应 business checkpoint 和 pending projection batch；任一数据库步骤失败则全部回滚。之后独立执行 Lucene 投影并推进投影 checkpoint。只有完整业务提交才能使新 hash 成为跳过同步的依据。投影失败保留 pending batch，显式 sync/rebuild 恢复，不要求跨存储原子提交。

不将 LLM 调用纳入数据库事务。简单地为整个服务加互斥锁会破坏并发读取，故采用专用写连接和与其隔离的读取连接生命周期；单次复合读取使用一致的读事务。业务事务不与读请求共享同一个 JDBC Connection。是否启用 WAL 不是此次设计的必要条件，先保证正确性，再依据授权后的测量评估。

不引入新依赖；优先复用现有表与批次结构。在线保存的源文件写入仍可能先于业务提交完成：此类失败必须报告可恢复状态，后续同步从源文件恢复，不能将文件写入声称为已回滚。保持 current revision 和历史快照对应关系，重复恢复不重复创建同一内容版本。

### 2. 保留有效的已提交搜索快照

搜索通过引用计数/租约获取复用的已提交 reader/searcher；刷新仅发生在成功提交之后，不通过 IndexWriter 的未提交视图提前暴露写入。

重建在串行写入边界内准备全部索引变更，不提交中间的 deleteAll。完整重建成功后提交并刷新 reader；失败则回滚未提交 writer 状态并重新打开 writer，保留旧 reader 和最近有效提交。禁止 close writer 意外提交失败重建的残留。旧 reader 在所有在途查询退出后关闭。

选择现有目录内的完整提交与回滚，避免目录切换引入 Windows 文件锁和路径迁移复杂性。若失败导致 writer 无法重新打开，搜索仍使用可用旧快照，写服务标为不可用/降级并允许显式 repair。

### 3. 按工作区管理资源生命周期

注册表锁仅用于短时查找和修改注册信息；文件 I/O、资源初始化、等待退出不持有全局锁。每个工作区有独立的 opening、ready、closing、closed/failed 生命周期及单次打开协调。

所有读写在接受时获取工作区使用租约，关闭先拒绝新租约，再等待已接受写任务与活跃读取退出。HTTP 等待超时不等于取消；超过关闭等待期限时仍不得关闭任务正在使用的资源，由工作区继续排空并在最后一个使用者退出后释放。重复关闭幂等，注册同一 ID 不得与旧实例关闭发生资源重叠。

服务停止时先停止接收请求，再排空工作区，最后释放 HTTP 执行资源。阻塞 LLM 请求应依赖其已有超时；不保证无限阻塞的外部调用能在固定时间内结束。

### 4. 分类重试与真实状态

仅对明确的暂态锁竞争等故障执行有界重试，最多三次尝试；权限、路径、数据校验、保存冲突、约束错误不自动重试。禁止笼统重放发生部分副作用的任意 Callable；优先重试已回滚的事务或有 durable batch 的幂等投影单元。延续现有 timeout-unknown 与 HTTP 状态映射。

状态读取同一一致性快照中的 business/projection checkpoint、pending batch 与生命周期信息。落后或存在未完成投影时，workspace 及总体状态为 degraded；成功追平后恢复 ready，永久资源失败保持 failed。Article 的 INDEXED 状态只在对应索引成功提交后推进。

### 5. 减少重复工作

projectBatch 和 rebuild 每篇文档只求值一次有效元数据，供其所有 chunks 复用；标签刷新也按文档执行。标题回退仅查询第一条有效标题，不加载全部 chunks。search 批量读取命中文档的活动状态和标签，维持原有过滤、排名与元数据优先级。

sync 先以流式 hash 判断是否变化，未变化且完整的文档直接跳过解码和 LLM。变化文档读取一次输入字节并重新计算最终 hash，以该输入建立完整的准备结果，避免先前 hash 与最终文本不一致。任何缓存必须按 workspace 和提交版本隔离。

### 6. 兼容分页契约

GET /api/v1/documents 增加可选 limit、offset、query。limit 范围 1..200，offset 为非负整数；只提供 offset/query 时默认 limit=50。query 对文档路径作不区分大小写的字面子串过滤，SQL 通配符视为普通字符；空 query 不过滤。分页按 path、id 稳定排序，返回原 documents 字段并增加 total、limit、offset。无上述参数时维持旧全量返回行为与字段。

列表与 total 来自同一读快照；不同请求之间不承诺静态快照，文档变更时允许用户刷新。管理页使用分页、搜索和空状态，不再构建全量下拉框；切换 workspace 或查询时清空旧页和选中项，避免响应乱序覆盖新状态。无效分页参数返回既有格式的 400 错误。

## Testing Decisions

主要边界为 KnowledgeBaseHttpService / 实际临时 HTTP 服务，使用临时 workspace、确定性 LLM stub 和可控故障。复用 KnowledgeBaseHttpAppTest、KnowledgeBaseTest、IncrementalSyncTest、WorkspaceContextTest 的模式。用户已确认这一安排。

少量底层测试用于在提交前、索引提交失败、关闭等待超时处注入故障，验证对外结果；不为每个内部方法新增 mock 层。并发测试使用 latch/barrier 控制顺序，不以 sleep 时间猜测成功。

性能比较使用同一机器、固定输入和相同配置，覆盖多 chunk 文档重建、重复搜索、无变化 sync 和分页列表，记录查询次数、延迟、峰值内存或分配量；不得只凭源码变化声称提速。当前不执行测试或 benchmark。

## Risks / Trade-offs

- [事务/连接生命周期变更] → 专用写连接、独立读事务、故障注入与并发读取验证；实施前明确说明边界。
- [Lucene 回滚影响后续写入] → 失败后重开 writer，验证后续 ingest/rebuild 和旧 reader 生命周期。
- [关闭排空等待较长] → 暴露 closing 状态，拒绝新操作，但不强关使用中的资源。
- [无参数旧列表仍为全量] → 保留兼容性，管理 UI 主动使用新分页接口。
- [现有历史半写入数据] → 新事务防止产生新问题；已有损坏不能仅靠 hash 自动识别，记录并采用显式重新摄取源文件的恢复步骤。

## Migration Plan

按 tasks.md 顺序实施并逐项验证，不创建额外任务生命周期。默认无需 schema 迁移；若实现证据表明必须增加持久字段，先更新本设计与迁移/回滚说明并取得所需授权。交付前用已有 workspace 验证读取和显式同步兼容；保留既有 API 路径与错误映射。索引可从已提交 SQLite 状态重建；回退代码前先排空工作区并保留用户数据。

## Task Mapping

AIW Task ID 与 OpenSpec change ID 均为 knowledge-base-reliability-performance。用户已同意采用父仓库 .ai/knowledge-base-reliability-performance/task.toml 作为唯一生命周期记录，规格位于 knowledge-base/openspec/changes/knowledge-base-reliability-performance。当前规划分支及 parent_branch 为 main，不在规格阶段创建实施 worktree。之后的 worktree 必须继承本次规格提交。

## Open Questions

%% 缺少生产规模及性能基线，实施阶段在授权后测量，不设置未经支持的绝对性能阈值。
%% 旧版本已经产生的半写入数据范围未知；自动修复存量损坏不在本次保证范围，需先检查并记录恢复说明。
