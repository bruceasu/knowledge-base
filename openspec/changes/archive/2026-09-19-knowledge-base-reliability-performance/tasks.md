## 1. 文档事务与恢复

- [x] 1.1 分离 JDBC 写连接与复合读取的连接/事务生命周期，保持工作区 FIFO 写入并发读取契约；补充读请求不观察半提交状态的测试。
- [x] 1.2 将摄取拆为事务外输入与 AI 准备、文档级 SQLite 原子提交和独立 Lucene 投影；原子提交 hash、chunks、自动元数据、checkpoint 与 pending batch，保留手工字段。
- [x] 1.3 补充 chunks 写入中断、业务提交后投影失败、显式重试和源文件变化的服务行为测试，覆盖 hash 不导致漏处理及 Article/revision 不重复。
- [x] 1.4 对在线保存后业务失败给出可恢复结果，并实现源文件到业务状态的显式恢复；记录历史半写入数据的检查及重新摄取步骤。

## 2. 搜索快照与重建

- [x] 2.1 引入可复用的已提交搜索快照租约，成功提交后刷新，在最后一个读取者退出后释放旧 reader。
- [x] 2.2 移除重建的空索引中间提交，失败时回滚并重开 writer，保证后续写入和 close 不发布失败残留。
- [x] 2.3 补充重建期间搜索、重建失败后搜索、失败后再次 ingest/rebuild、在途 reader 安全释放的测试。

## 3. 工作区生命周期、重试与状态

- [x] 3.1 将工作区初始化和关闭等待移出注册表全局锁，实现按工作区的单次打开、closing 状态与读写使用租约。
- [x] 3.2 调整关闭顺序，停止接受新操作后排空已接受读写；关闭等待超时保留活跃资源，完成后幂等释放；禁止同一存储的新旧实例重叠。
- [x] 3.3 分类暂态与永久异常，仅对已回滚事务或幂等投影执行最多三次尝试；保持现有 HTTP 错误映射及 timeout-unknown 语义。
- [x] 3.4 让 readiness、pending batch 和 checkpoint 一致，索引提交成功后才推进 Article INDEXED；增加失败降级及追平恢复测试。
- [x] 3.5 使用 latch/barrier 补充 A 关闭不阻塞 B、并发打开、关闭等待超时、重复关闭和在途读写资源所有权的服务/底层测试。

## 4. 降低重复查询与摄取开销

- [x] 4.1 按文档复用投影/重建元数据及标签刷新，将标题回退改为有限查询，搜索批量获取命中文档活动状态和标签；补充结果等价测试。
- [x] 4.2 在 sync 解码前检查流式 hash，仅对变化文档准备内容；最终 hash 与解码使用同一输入字节，保留 pending projection 恢复与编码兼容性。
- [x] 4.3 准备可重复的性能测量步骤和固定数据集，覆盖多 chunk 投影、连续搜索、无变化同步与列表加载；记录可测指标，不运行 benchmark 或宣称性能提升。

## 5. 兼容分页与管理页面

- [x] 5.1 实现带 limit/offset/query 的文档列表查询与 HTTP 参数校验，稳定排序且 total 与页数据同快照，保持无参数旧响应兼容。
- [x] 5.2 将管理页文档选择改为分页和路径过滤，处理空结果、翻页、workspace/过滤切换及乱序响应。
- [x] 5.3 补充分页边界、字面通配符、旧客户端、workspace 隔离的接口测试，并记录管理页切换和编辑选中项的人工验收步骤。

## 6. TODO 与交付文档

- [x] 6.1 更新 API 分页、维护恢复、关闭状态的使用说明；核对实现遵循现有 ADR，如需改变已接受决策先更新设计并说明原因。
- [x] 6.2 在完成相应实现后更新长期 capability specs，逐项核对本变更场景；更新 TODO、Verification 与剩余 %% 说明，不提前标记未执行验证完成。

## 7. Verification

- [x] 7.1 实施后申请聚焦测试授权，建议从 Maven 的 KnowledgeBaseTest、IncrementalSyncTest、LuceneSearchIndexTest、WorkspaceContextTest 和 KnowledgeBaseHttpAppTest 开始；获授权后记录实际命令、结果和失败，未授权则保留待验证状态。
- [x] 7.2 在获得对应授权后执行完整测试/构建及固定数据集性能比较，记录环境、数据规模与前后结果；未执行不得声称通过或提速。
- [x] 7.3 完成并记录旧 workspace、旧 CLI/API、分页管理页面、失败恢复和多工作区关闭的兼容性验收，明确剩余限制。

## Planning Evidence

- 2026-09-18：已基于核心源码、既有规范和两份 ADR 完成静态分析；本变更是规划，全部实施项待办。
- 用户确认：以 KnowledgeBaseHttpService / HTTP 接口为主要测试边界，仅对故障注入和资源关闭补充底层测试。
- 用户同意采用父仓库 .ai 中的 AIW Task 记录；不在 OpenSpec 目录复制生命周期记录。
- 已完成 1.2、2.1-2.2、3.1-3.4、4.1、5.1-5.2、6.1；其余清单项保持待办，尤其是独立 JDBC 读连接、故障/并发测试和性能测量。
- 2026-09-18：使用 JDK 25 执行 `mvn -DskipTests compile` 成功；未执行测试、lint 或 benchmark，不创建实施 worktree。
- 2026-09-18：补充在线保存后的可恢复响应、流式预检 hash、分页独立读事务与性能测量计划；未执行测试或 benchmark。分页读事务覆盖 5.1 的同快照要求，通用读连接隔离仍待 1.1 完成。
- 2026-09-18：使用 JDK 25 执行 `mvn -DskipTests clean compile` 成功（编译 89 个源文件）；未执行任何测试、lint 或 benchmark。
- 2026-09-18：为 1.1 完成写连接与核心复合读取的连接生命周期分离：读操作使用短生命周期 SQLite 读事务，嵌套查询复用同一快照；分页继续使用同一读快照。已用 JDK 25 执行 `mvn -DskipTests clean compile` 成功；按用户要求未执行测试，故 1.1 的测试部分及复选框保持待办。
- 2026-09-18：补充管理页分页、过滤、工作区切换和编辑选中项的人工验收步骤，见 `docs/management-page-acceptance.md`；5.3 的接口测试未执行，复选框保持待办。
- 2026-09-18：用户授权测试后，先执行 `mvn -Dtest=KnowledgeBaseTest,IncrementalSyncTest,LuceneSearchIndexTest,WorkspaceContextTest,KnowledgeBaseHttpAppTest test`；首次发现 Xerial SQLite 不支持连接后 `setReadOnly(true)`，已改为读连接的 `PRAGMA query_only = ON`，再次执行聚焦测试 19 项通过。新增 `KnowledgeBaseHttpAppTest` 分页接口覆盖后单类 10 项通过。
- 2026-09-18：执行 `mvn test` 与 `mvn verify` 均成功，测试 88 项、零失败/错误/跳过，且已生成 shaded JAR；未执行固定数据集性能比较，不能声称性能提升。
- 2026-09-18：新增 `KnowledgeRepositoryTest#isolatedReadTransactionSeesOnlyThePriorCommittedDocument`，以 latch 固定未提交的文档替换与读取顺序；`mvn -Dtest=KnowledgeRepositoryTest test` 通过 9 项，验证独立读事务不观察半提交 hash/chunks。
- 2026-09-18：新增 `LuceneSearchIndexTest#uncommittedRebuildChangesNeverReplaceTheCommittedSearchSnapshot`；测试发现搜索中的 reader 刷新会暴露未提交 Lucene 变更，已改为仅在成功 commit/初始化/回滚恢复时刷新。`mvn -Dtest=LuceneSearchIndexTest clean test` 通过 2 项，覆盖未提交重建可搜索旧快照、回滚后仍可查及后续成功提交。
- 2026-09-18：扩展 `KnowledgeBaseHttpAppTest#documentListSupportsLiteralPagedWorkspaceScopedQueries`，覆盖分页边界、字面 `%`/`_`、旧无参数响应及 default/team workspace 分页隔离；`mvn -Dtest=KnowledgeBaseHttpAppTest clean test` 通过 10 项。
- 2026-09-18：新增 `WorkspaceContextTest#closingOneWorkspaceDoesNotBlockReadsInAnotherWorkspace`，以 latch 固定 A 的活跃读租约和关闭等待，验证 B 仍可读取；`mvn -Dtest=WorkspaceContextTest clean test` 通过 2 项。
- 2026-09-18：新增 `IncrementalSyncTest#changedSourceCreatesOneRevisionAndRepeatedSyncDoesNotDuplicateIt`；`mvn -Dtest=IncrementalSyncTest clean test` 通过 2 项，验证源内容变更后只创建一次新 revision，重复无变更 sync 不重复创建。
- 2026-09-18：新增 `IncrementalSyncTest#interruptedChunkReplacementRollsBackAndLaterSyncRecovers`，以 SQLite trigger 注入 chunks 写入中断；`mvn -Dtest=IncrementalSyncTest clean test` 通过 3 项，验证失败保留旧已提交内容，移除故障后显式 sync 恢复。
- 2026-09-18：新增包内 `KnowledgeBase.IndexFactory` 与 `LuceneSearchIndex.CommitHook` 测试 seam（默认生产路径无变化），并新增 `IncrementalSyncTest#projectionCommitFailureLeavesDurableBatchForExplicitRecovery`；`mvn -Dtest=IncrementalSyncTest clean test` 通过 4 项，验证投影失败后的 durable pending batch 与显式恢复。
- 2026-09-18：新增 `LuceneSearchIndex.SearchHook` 包内测试 seam 与 `LuceneSearchIndexTest#inFlightSearchRetainsReaderWhileNewSnapshotPublishes`；`mvn -Dtest=LuceneSearchIndexTest clean test` 通过 3 项，验证在途 reader 在发布新快照时可安全完成。
- 2026-09-18：扩展 `WorkspaceContextTest`：以 latch 覆盖 A 关闭不阻塞 B、关闭等待超时后在途 read 仍持有资源、同一工作区并发 open 返回同一实例及重复 close 幂等；使用 JDK 25 执行 `C:\green\apache-maven-3.9.9\bin\mvn.cmd -Dtest=WorkspaceContextTest clean test`，5 项通过。
- 2026-09-18：使用 JDK 25 和 Maven 3.9.9 执行 `clean verify`，98 项测试全部通过并产出 shaded JAR。随后运行固定数据集 `PerformanceMeasurement`：100 篇文档、20 篇多 chunk、340 chunks；结果见 `docs/performance-results.md`。历史变更前基线不可用，文档明确没有声称提速。
- 2026-09-18：完成临时 workspace 的 CLI/HTTP/生命周期/恢复兼容性验收，明细及未执行的历史 workspace 迁移和浏览器人工步骤见 `docs/compatibility-acceptance.md`；`node --check webui/app.js` 通过。
- 规格完整性、AIW ID 映射与 Git 提交结果在交付时报告。

## Remaining Notes

%% 性能基线与实际文档规模未知，实施测量后记录结果。
%% 数据库事务、资源关闭与新增分页参数属于需解释影响的变更；实施前按 design.md 说明范围，新增依赖或 schema 迁移需要另行确认。
%% 历史版本可能已留下半写入数据；尚未执行数据检查，恢复方式为先备份工作区，再对相应根目录运行显式 sync 并核对 pending projection batches。
