# FD-001 Worker 实施报告 r1

- FD：FD-001；来源事件：`FD-001-000003-design-ready`。
- Worker session：`kb-fd001-worker-20261030-c73b12`。
- 工作区：`.wt/FD-001`，分支 `feature/FD-001`，父分支 `main`，坐标与受管理 workspace.json 一致。
- 审查基线：父分支计划提交 `e1a45a0`，审查该基线到本报告所在提交的完整 diff。
- 结论：实现已准备好独立静态审查；不是 Reviewer pass，不代表已部署或端到端验收。

## 交付结果

1. `PersonalKnowledgeSettings`、真实 socket loopback 绑定、`PersonalAccessGate` 和 Caddy/Authelia 模板构成入口边界。认证在正文解析前执行；浏览器写操作校验 Origin/CSRF；精确秘书路由校验 token 摘要/主体/scope，集合过滤在应用层执行。旧业务 API 的响应格式不变，个人模式收紧浏览器请求认证及 JSON 类型。
2. `PersonalHttpRoutes` 按交接契约挂载 capabilities、search、read、notes、creation status。请求/响应有字节预算、统一 error/requestId，读取有当前 revision 与主体绑定签名游标。文档应用接口不向 transport 暴露 SQLite。
3. `PersonalCatalog` 在文档数据库上增加 v1 表。首次迁移先 VACUUM INTO，再事务创建；稳定 serviceId 和游标签名密钥随数据库备份恢复。现有资料映射到默认集合；原有文档 ID 和表语义保留。
4. `PersonalKnowledgeService` 持久化主体+幂等键、规范化请求、预分配身份和文件路径。写原文、元数据、引用快照和索引分步骤恢复；202 前回执持久化；终态保留，删除时阻止后台重建。文档 transport 和个人 worker 共用 KB monitor，保护共用写连接。
5. 上传每请求一个 multipart 文件，实际内容 100 MB，最多两个并发，持久待处理队列 128。原件、提取正文与笔记分存。ZIP 展开预算、路径/符号链接/冲突检查、HTML 纯文本、PDFBox 3.0.8 与 POI 6.0.0 文本提取接入；OCR 不启用。失败可按原任务重试，原件下载也受认证保护。
6. `personal.html/js/css` 提供上传、笔记、处理状态、最近资料、纯文本阅读及受保护原件下载；源码与 bundled 资源一致。浏览器待确认保存保留原幂等键和请求，不因超时换键。
7. MCP 的五个生产类、五个专属测试类和 SDK 依赖移除；共享 facade/retrieval/context 保留。稳定规格、README、操作指南和独立秘书交接同步更新。没有修改秘书项目。

## 验收映射与证据边界

| FD 验收 | 静态实现证据 | 未执行的运行证据 |
| --- | --- | --- |
| 1 API 与隔离 | PersonalHttpRoutes、PersonalKnowledgeApplication、交接契约 | 秘书跨机联调、contract fixtures |
| 2 引用/版本/权限 | PersonalReadService、带身份的游标签名、阅读路由 | 中文样本召回、更新/删除并发 |
| 3 上传边界 | HTTP 有界流和服务计量；multipart 预留 65,536 字节开销 | 恰好 100 MB、超一字节、chunked |
| 4 原件与格式 | PersonalTextExtractor、OfficeTextExtractor、ArchiveBudget、持久导入状态 | OneNote 真实包、损坏 ZIP、扫描 PDF |
| 5 幂等与恢复 | personal_operations 唯一键、持久 pending、确定路径、终态与删除记录 | 并发同键、响应丢失、进程崩溃 |
| 6 远程边界 | loopback、前置 Gate、代理密钥、Caddy 精确路由和 Authelia two_factor | 后端直连、伪造头、停 Authelia |
| 7 兼容与迁移 | 增量 schema、备份/回滚说明、旧 HTTP 保留、MCP 退役 | 已有生产 workspace 升级/回滚 |
| 8 如实报告 | 本报告及 docs/personal-knowledge.md | 没有把未跑项目当作通过 |

## 实际命令

- PATH 下第一次 `mvn -o -DskipTests compile` 失败，因为没有 mvn。查到已有 Maven 和 JDK 17 后使用明确路径；没有安装工具。
- Java：`C:/green/graalvm-jdk-17.0.8+9.1`；Maven：`C:/green/apache-maven-3.9.9/bin/mvn.cmd`。
- 配置/前置认证切片：`-o -DskipTests compile` 成功，287 源文件，13.480 秒。
- 加入解析库后离线编译因缺依赖失败。用户明确批准下载后，`-DskipTests compile` 成功，290 源文件，15.760 秒。
- 后续实际源代码变化分别执行 `-o -B -ntp -DskipTests compile`，295、296、291 源文件编译成功；最后一次为 291 源文件、7.913 秒。保留既有 deprecated/unchecked 警告。
- 最终静态命令：`git diff --check` 无输出；五对 WebUI 文件 SHA-256 一致；生产/测试源码及 pom 中不再有 MCP SDK/包引用。
- AIW 使用 fd show/claim/emit 与 git wt add/status，Git 使用路径限定的本地提交；尚未合并、归档或 push。

## 限制与风险

未运行测试、最终构建、服务/浏览器、代理配置验证、真实文件提取、跨机联调和故障恢复实验。用户只授权下载依赖及仅编译。静态审查不能证明部署网络隔离、解析库对真实复杂文档的行为或崩溃窗口；上线前须完成运行验收。

ZIP 原包保留相对资源关系；首版阅读是纯文本，附件保留在原包中供下载，不提供原 HTML 的交互式重放。提取正文有 10,000,000 Java 字符上限，DOCX XML 有 20 MB 上限。完整可移植导出、OCR、自动目录监听和语义向量检索不在本 FD。

原设计/下载 blocker 均已解决并保留历史。建议下次依赖审批一并声明下载需求；模板缺失的通用工具改进不混入本次产品交付。
