# FD-001: Personal knowledge service and secretary integration

**Status:** Complete
**Revision:** 7
**Priority:** Medium
**Review policy:** Independent
**Evidence policy:** Markdown

## Problem

个人资料分散于文件、OneNote HTML 导出和笔记中，缺少统一组织与可追溯查询。知识库需要保存用户可掌控的原件和笔记，通过 HTTP 向独立主机上的个人秘书提供资料；WebUI 用于远程上传、创建笔记、阅读和维护。

当前已有 Java 17、SQLite、Lucene、文档版本和单 workspace 组合根，但没有目标秘书契约、完整远程访问边界和持久化上传任务。用户已授权创建 FD 并自动实现；本 FD 是首版知识库端交付，秘书代码由用户单独交给另一项目实现。

## Solution

### 方案比较与决定

1. 秘书直接打开知识库文件或数据库：异机部署和单进程资源所有权不允许，排除。
2. 扩展 MCP：用户已明确不采用，排除。
3. 在现有应用层上增加个人资料服务与 HTTP 适配层：采用。保留既有检索、版本和来源能力，避免重新建立一套全文检索引擎。

跨项目接口唯一来源为 `docs/handoffs/personal-secretary-knowledge-handoff.md`，契约 1.0，前缀 `/api/v2/secretary`。提供 capabilities、search、documents/{id}、notes、note-creations/{key}；未知请求字段拒绝，JSON 请求上限 256 KiB、响应上限 1 MiB。不得把规划中的 API 描述为现已可用。

### 模块与职责

- `runtime`：单 workspace 生命周期、配置、共享服务装配、启动恢复及关闭。
- 新个人资料应用接口：资料身份、集合、受控读取、笔记创建、任务与授权规则；HTTP 层不访问数据库。
- 文档 repository：事务、稳定外部 ID、集合关系、创建回执与导入任务；复用已有 SQLite 连接与事务边界，禁止跨独立数据库假设原子提交。
- `http` 与底层 `me.asu.http`：精确路由、鉴权、有限请求体读取、响应封装和错误映射。
- `webui` 与 `src/main/resources/webui`：同步更新上传、笔记、状态与受保护阅读入口。
- 部署文档和示例：Caddy + Authelia，提供参数化配置，不部署、不生成真实账号或凭证。

### 安全与资源设计

浏览器路径统一由 Caddy forward_auth 交给 Authelia；秘书端点精确匹配并由应用验证 Bearer、scope 和集合权限。应用不以 Authorization 头存在作为放行依据。后端绑定 loopback；容器示例不得发布后端端口。清理外来代理身份头，写请求校验 Origin/CSRF。附件和资料阅读也经过相同身份边界。

建议首版 Authelia 使用单用户本地文件账号库与 TOTP；账号库、会话和二次验证存储纳入加密备份，恢复说明要求通过主机管理权限执行，不能公开恢复接口。域名、邮件重置通道和密钥作为部署必填参数；缺失时明确不具备远程上线条件。

上传每请求一个文件/ZIP，最多 100,000,000 字节，流式计量；代理容纳有界 multipart 开销。超限 413 并清理临时文件，不信任 Content-Length。ZIP 展开预算独立于上传限制。总存储配额首版可配置，未配置必须明确为不设逻辑总配额，仍处理磁盘不足；不得把 100 MB 当作全库配额。

### 持久化与兼容性

新增资料 ID/集合映射、导入任务和创建回执需要版本化的增量 schema；保持已有文档 ID、版本、原件和 HTTP 响应。迁移只增加表/索引，先备份，失败回滚并拒绝进入 ready；不删除旧数据。具体 SQL 与事务调用链由 1.2 的设计产物确定后再实施。

笔记回执以稳定调用主体和幂等键唯一约束，记录规范化请求摘要、预分配资料身份和状态；同键同内容重放、不同内容 409。202 前持久化接收记录；文件写入、文档登记与回执终态之间用可恢复状态机衔接，重启对账不能重复创建。索引失败不等于笔记保存失败。永久保留首版回执/删除墓碑。

MCP 移除安排在 HTTP 替代能力完成之后；删除显式启动入口、专属依赖和专属测试，同时更新稳定规格，不删除仍被 HTTP 使用的 agent facade、检索或上下文模块。这是用户已明确要求的兼容性变化。

## Scope

包含：完整秘书 API 1.0 服务端、授权边界、WebUI 上传和笔记入口、原件/持久导入、Markdown/TXT/HTML/ZIP/文本 PDF/DOCX 首批处理、认证部署示例、受保护阅读、MCP 移除及文档。

不包含：秘书项目代码、OCR、旧 DOC、自动目录监听、语义向量检索、完整可移植导出产品、实际部署/发布。完整导出及恢复演练仍保留于总体方案后续阶段；本次提供迁移前备份与回滚说明。

已确认（2026-10-30）：ZIP 总展开上限 500,000,000 字节、2,000 个条目、单个展开文件 100,000,000 字节、压缩比 100:1，禁止嵌套解压。超限保留原包并报告处理失败。

已确认（2026-10-30）：允许新增 PDF/DOCX 文本解析依赖，不启用 OCR。PDF 采用 Apache PDFBox 3.0.8（官方 https://pdfbox.apache.org/ 已发布）；DOCX 采用 Apache POI 的 OOXML 文本适配，版本在依赖工作项依据官方发布记录锁定。本机 PDFBox 缓存仅有 2.0.x，不能因离线缓存而降级选用旧版本；新增依赖的下载与编译结果单独记录。

## Work items

每项预计不超过半天；表内规模均为 S，难度 L/M。此为规划估计。设计项未完成时依赖它的实现项不能启动；实施开始后保持编号稳定。



- [x] 1.1 解决 ZIP 与解析依赖 Gate，记录确切首版格式和限制（S/M；依赖：用户决定）。
- [x] 1.2 形成 schema/状态机设计：现有文档映射、事务所有权、崩溃点、迁移和回滚；无跨库原子性假设（S/M；依赖：1.1）；见同目录 design.md。
- [x] 1.3 更新个人知识服务稳定规格，映射 API 契约和验收编号（S/L；依赖：1.2）。
- [x] 2.1 配置解析：publicBaseUrl、稳定 serviceId、绑定地址、凭证引用；非法配置启动失败（S/M；依赖：1.3）。
  - [x] 2.1a 配置解析、loopback 实际监听和旧 Configuration 构造签名保留；静态证据见 configuration.md。
  - [x] 2.1b 随 3.1 初始化持久 serviceId 并接通个人模块；2.1 仅在此项完成后完成。
- [x] 2.2 秘书凭证验证、稳定主体、scope/集合授权；缺凭证 401、无权资源 404（S/M；依赖：2.1）。
- [x] 2.3 浏览器代理身份边界、Origin/CSRF 和全路由覆盖；不能直连绕过（S/M；依赖：2.1）。
- [x] 2.4 参数化 Caddy/Authelia 示例与账户/恢复说明，精确区分秘书和浏览器路径（S/M；依赖：2.2,2.3）。
- [x] 3.1 增量 schema、资料外部身份/集合映射和迁移失败回滚（S/M；依赖：1.2,2.1）。
- [x] 3.2 API 编解码、统一错误和字节预算；capabilities 仅声明已实现能力（S/M；依赖：2.2,3.1）。
- [x] 3.3 搜索适配、权限前置过滤、格式/日期过滤、来源和新鲜度（S/M；依赖：3.2）。
- [x] 3.4 版本检查与身份绑定签名游标，分页码点边界/过期行为符合契约（S/M；依赖：3.2）。
- [x] 4.1 幂等回执存储、请求规范化和唯一键冲突处理（S/M；依赖：3.1,3.2）。
- [x] 4.2 笔记创建状态机、预分配身份、重启恢复和删除墓碑（S/M；依赖：4.1）。
- [x] 4.3 引用授权/版本校验与快照；创建/对账端点符合 202/200/409 语义（S/M；依赖：3.4,4.2）。
- [x] 5.1 托管原件流式上传、100 MB 边界、临时文件清理；禁止服务器路径输入（S/M；依赖：2.3,3.1）。
- [x] 5.2 持久导入队列、单 worker、有界接受任务、崩溃恢复/失败重试（S/M；依赖：5.1）。
- [x] 5.3 Markdown/TXT/HTML 正文提取，原件与派生正文分离，HTML 不执行主动内容（S/M；依赖：5.2）。
- [x] 5.4 ZIP 有界展开、路径安全、资源相对关系和缺失诊断（S/M；依赖：1.1,5.3）。
- [x] 5.5 文本 PDF 提取适配或经确认的仅保存策略，扫描件不得标为可全文查询（S/M；依赖：1.1,5.2）。
- [x] 5.6 DOCX 提取适配或经确认的仅保存策略，内部 ZIP 同样受限（S/M；依赖：1.1,5.2）。
- [x] 5.7 导入正文接入现有检索；区分原件已保存、可查询、部分、失败（S/M；依赖：5.3,5.4,5.5,5.6）。
- [x] 6.1 WebUI 上传/笔记入口与任务状态、错误重试；两份资源一致（S/M；依赖：4.3,5.7）。
- [x] 6.2 受保护资料阅读及附件下载；API 来源链接可解析，HTML 不加载外部资源（S/M；依赖：3.4,6.1）。
- [x] 7.1 移除 MCP 专属入口/依赖/测试，保留共享领域模块，更新相关稳定规格（S/M；依赖：3.3,3.4,4.3,6.2）。
- [x] 7.2 同步操作说明与秘书交接的实现状态、迁移/备份/回滚及限制（S/L；依赖：2.4,7.1）。
- [x] 7.3 最终静态检查、实际授权的编译证据、Worker 报告和独立 Reviewer 交接（S/M；依赖：7.2）。

- [x] 1.1–1.3 设计与规格完成。
- [x] 2.1–2.4 远程身份边界完成。
- [x] 3.1–3.4 只读 API 完成。
- [x] 4.1–4.3 幂等笔记完成。
- [x] 5.1–5.7 上传与首批格式完成。
- [x] 6.1–6.2 WebUI 完成。
- [x] 7.1–7.3 兼容性清理、证据和交接完成。

## Acceptance

1. 秘书按独立交接文档调用五类端点，字段、状态码、权限、分页、预算和幂等语义一致；不访问共享磁盘。
2. 搜索引用可在已登录浏览器打开；无权访问、已删除或版本变化不得泄漏或误读正文。
3. 恰好 100,000,000 字节上传可接受；超出一字节及无 Content-Length 超限均 413，清理残留。
4. 支持格式的原件完整保留，提取失败不丢原件；ZIP 资源限制按批准值执行。
5. 并发同键、超时重试、进程重启最多产生一个笔记；保存和索引状态分别报告。
6. 无登录附件请求、伪造身份头、无 token 秘书请求及 Authelia 故障均无法取得资料；外部不能连接后端端口。
7. 新旧文档共存，新增 schema 失败不破坏原数据；已有 HTTP 契约保留，MCP 明确退役。
8. 未实现/未验证能力如实标明；不以静态审查替代实际远程安全验证。

## Verification

- 已执行：读取两份方案、runtime 文档应用边界、HTTP 路由、pom.xml、单 workspace 稳定规格及当前 UI/MCP change；`git status --short`、`git branch --show-current`、`aiw fd list/new/show/claim`。
- 已确认：父分支 main；此前仅两份方案文件未跟踪；无配置 AIW_FD_ROLE_RUNNER。CLI 生成的 FD 位于 `docs/features/FD-001/`，使用其实际路径，不手工迁移。
- 实施前：解决上面的 Gate，完成 1.2 schema 设计，精确提交方案及 FD；通过 `aiw git wt add FD-001` 隔离实现并核对 workspace.json。
- 实施后：每项静态追踪调用链和边界；最终只做一轮集中静态校验。按仓库授权选择 compile-only 命令，不运行测试、最终构建、网络下载或部署。编译若需下载则记录阻塞，不绕过离线限制。
- 独立 Reviewer 检查全部验收项和真实 diff，最多三个 Reviewer 结果；当前审查次数为 2；r1 changes-requested 的三项已修复，r2 verification-passed，见 ../reviews/FD-001-review-r2.md。
- 已由 Worker 运行 Java 17 compile-only（最终 291 源文件，7.913 秒）；Reviewer 未重跑。未运行：测试、真实样本解析、并发/崩溃恢复、代理故障及外部直连验证。它们不是已通过的证据；远程上线前必须完成对应运行验收。

## TODO

- [x] 通过 CLI 分配 FD-001 并领取 Planner 事件。
- [x] 记录方案、兼容性、安全边界、工作拆分和验收。
- [x] 解决设计 Gate，补齐持久化设计后发出 design-ready。
- [ ] 自动实施已开始；独立审查、本地 squash 合并和归档尚未完成。

Planner session：`kb-fd001-planner-20261030-a91f38`。
来源事件：`FD-001-000002-design-requested`。

Worker session：`kb-fd001-worker-20261030-c73b12`；来源事件 `FD-001-000003-design-ready`。父分支 main、feature/FD-001、.wt/FD-001 已与主工作区 `.ai/fd/FD-001/workspace.json` 核对一致。已完成的切片按独立提交保存；整体仍未完成。

## Sources

- Issue: none；用户直接授权 FD auto。
- `docs/handoffs/personal-knowledge-integration-plan.md`
- `docs/handoffs/personal-secretary-knowledge-handoff.md`
- `openspec/specs/single-workspace-knowledge-service/spec.md`
- `openspec/changes/ui-mcp-experience-optimization/`（现有改动边界，保留历史证据）

## Worker 增量证据

- 2.2a 凭证文件校验、摘要匹配、主体与 scope 检查已实现；集合对象授权待 3.1–3.4 接通，2.2 尚未整体完成。
- 2.3a 全局前置身份 Gate、loopback+代理密钥校验、Origin/CSRF 已实现，既有 WebUI 同步添加 CSRF 请求头；新资料路由尚未实现，整体仍未验收。
- 2.4a Caddy 精确路由和账号/恢复说明已提供；Authelia 安装版本的完整配置及实测未完成。
- 使用 C:/green/graalvm-jdk-17.0.8+9.1 和 C:/green/apache-maven-3.9.9/bin/mvn.cmd 执行 -o -DskipTests compile 成功（287 个源文件，13.480 秒）；未运行测试。初次 PATH 下 mvn 不存在，改用本机明确路径后成功。
- 历史检查点：当时新增依赖未下载；现已获用户授权并下载、编译成功。

### 当前检查点

- 3.1a 已编写 additive schema、VACUUM INTO 备份、迁移回滚和 serviceId/游标密钥初始化，启动接线已加入；未编译验证，旧文档映射和后续操作服务仍待实现，3.1 未完成。
- 5.4a/5.5a/5.6a 已编写可独立接入的 ZIP 预算预检和 PDF/DOCX 文本提取适配器；尚未接入持久队列、未编译验证，不标记父工作项完成。
- 锁定解析依赖：PDFBox 3.0.8、POI OOXML 6.0.0；版本来自官方发布说明。新增依赖后离线编译失败，不能把之前成功编译套用于后续改动。
- 历史 Gate：`../reports/FD-001-blocker-20261030-dependency-download.md`，现已解决。原 ZIP/依赖选型 Gate 已解决。
- 自动流程仍处于 Worker，没有 implementation-ready、Reviewer 结果、本地合并或归档。全部剩余工作项继续保留，不将部分实现描述为整体交付。

### API 应用层与路由接通

- 下载 Gate 已解决：用户明确确认下载，新增依赖及后续 296 源文件均已通过 Java 17 compile；未运行测试。
- 已接通五类秘书路由、服务身份/集合授权、过滤前置检索、版本/签名游标、持久创建回执和单线程导入队列。
- 新增 BROWSER_READ 与浏览器上传/导入状态适配，旧 HTTP 响应保持原格式；全局浏览器写请求增加 CSRF 限制。
- 个人导入复用文档索引，但明确禁用隐式 LLM/embedding 网络调用；所有文档应用操作和个人队列共用 KnowledgeBase monitor，避免共用 SQLite 写连接交错。
- 下一步为 WebUI、ZIP 诊断和故障恢复静态修正，完成后才更新对应父工作项验收状态。

## Worker 最终交接证据

第二轮交接：第一轮三个 P2 已修复，见 `../reports/FD-001-implementation-r2.md`。搜索投影完整性、明确拒绝后的草稿恢复和同字节快照读取已更新；Java 17 离线 compile 成功（291 源文件，6.154 秒）。独立复审尚待执行，测试和运行验收未执行。

本次 1.1–7.3 的实现和文档切片已完成，独立 Reviewer 尚待执行。上面的增量检查点为过程历史，不再表示当前未实现。完整证据见 ../reports/FD-001-implementation-r1.md。

- 新增 serviceId/凭证/代理边界、五类秘书 API、持久幂等笔记、上传/导入队列、首批格式与纯文本阅读已接通。
- Caddy 和现代 Authelia v4 模板、账号存储及恢复说明已写入 deploy/personal；未实际安装或校验配置。
- MCP 专属代码、SDK、测试和操作文档已更新；共享 facade/检索/context 保留。
- 最终 Java 17 离线 compile 成功，291 个源文件，7.913 秒；源码/bundled WebUI 哈希一致，git diff --check 无输出。
- 未执行测试、最终打包、服务启动、真实浏览器/代理/跨机联调和崩溃恢复实验。以上未执行项目保留为运行风险，未被描述为通过。
- 已复核两组 blocker：ZIP/依赖方案确认和 Maven 下载均已解决；可复用改进为首次依赖询问同时包含下载需求，后续可单独改进技能，本 FD 不修改通用技能。


## Reviewer r1

独立审查结论 changes-requested：搜索完整性、明确拒绝后的笔记编辑恢复、正文与哈希同快照三项需要修复。报告：../reviews/FD-001-review-r1.md。来源事件 FD-001-000004-implementation-ready；Reviewer session kb-fd001-reviewer-20261030-7e42a1。未运行测试、编译或部署；本地合并与归档尚未完成。


## Reviewer r2

独立复审 verification-passed。审查提交 6d3d51f，来源事件 FD-001-000006-implementation-ready；Reviewer session kb-fd001-reviewer-20261030-7e42a1。r1 三个 P2 均已解决，未发现新的阻断问题。报告：../reviews/FD-001-review-r2.md。实际编译以 Worker r2 的 291 源文件、6.154 秒为最新证据；测试、真实远程/解析/恢复验收未运行。本地交付与归档仍由 PM 继续，不提前标记 TODO 完成。
