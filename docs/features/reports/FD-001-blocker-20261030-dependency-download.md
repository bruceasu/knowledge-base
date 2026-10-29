# FD-001 新解析依赖离线编译阻塞

<!-- aiw-data: FD-001-blocker-20261030-dependency-download.json -->

- 状态：已解决；记录日期 2026-10-30。
- 阶段/角色：Worker / worker。
- 来源事件：`FD-001-000003-design-ready`。
- Session：`kb-fd001-worker-20261030-c73b12`。
- 工作区：`.wt/FD-001`，分支 `feature/FD-001`，父分支 `main`，坐标已与主工作区 workspace.json 核对。

## 事实与原因

用户已批准 ZIP 安全预算和新增 PDF/DOCX 解析依赖，原 `FD-001-blocker-20261030-a91f38` 已解决。新增依赖选用 PDFBox 3.0.8 与 POI OOXML 6.0.0，官方发布依据分别为 https://pdfbox.apache.org/ 和 https://poi.apache.org/download.html 。本机缓存缺少这些版本及其 POM，Maven 离线依赖解析失败。没有依赖下载或测试授权。

## 实际尝试

1. PATH 下 `mvn -o -DskipTests compile` 无法找到 Maven；PATH 的 Java 是 8。
2. 找到已有工具后，设置当前进程 JAVA_HOME 为 `C:/green/graalvm-jdk-17.0.8+9.1`，使用 `C:/green/apache-maven-3.9.9/bin/mvn.cmd -o -DskipTests compile`。新增解析库之前的配置/身份入口切片编译成功，287 个源文件，13.480 秒。没有运行测试。
3. 添加解析库与适配器后，以相同离线命令编译，0.310 秒后报告两个新依赖无法解析。尚未进入 Java 编译阶段；后续持久化、请求体时序修正和解析代码不能引用此前成功结果作为自己的编译证据。
4. 已异步请求允许 Maven 下载这些依赖和传递依赖并仅编译，尚无答复。不安装其他工具、不启动服务、不测试、不部署。

## 当前进度与下一步

已提交配置/loopback 和前置鉴权切片；另已写入增量 schema/备份/稳定身份、ZIP 预算预检及 PDF/DOCX 适配器，但未完成全部 API、上传队列、WebUI 产品入口或 MCP 移除。这些功能不能宣称可用。没有发出 implementation-ready，也没有 Reviewer 结果、合并或归档。

人工决定：是否允许对当前 pom 运行明确路径的 `mvn.cmd -DskipTests compile`，通过 Maven 下载所需依赖及传递依赖。允许后先验证新依赖 API 与 Java 17 的兼容性，再继续尚未完成的 Work Items；若依赖 API 不兼容，保持明确版本调整记录，不静默降低安全版本。

适用技能 `.agents/skills/fd-workflow/SKILL.md`：自动流程 “does not authorize tests, final builds, network access”。仓库 `prompts/core/resource-budget.md` 也把 network calls and dependency downloads 默认预算设为 0。此处没有收到自动审批拒绝，是当前授权范围未包含下载。

可复用改进：在新增依赖授权问题中一并说明是否需要 Maven 下载，以及 Java/Maven 的明确路径，避免再次分轮确认。本次不擅自修改通用技能。模板仍不存在，沿用前一事实记录的字段结构。

## 解决记录

用户已确认允许下载依赖并仅编译。Maven 下载完成，Java 17 编译成功（290 源文件，15.760 秒）；后续新增应用层及 HTTP 路由离线编译亦成功。原失败与授权请求保留为历史。本 Gate 不再阻塞，继续剩余工作项。
