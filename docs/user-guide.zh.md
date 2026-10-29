# knowledge-base 用户与部署手册

本文是当前单工作区版本的快速开始、部署、使用和运维说明。

> 一个进程只支持一个本地 workspace。旧 `/api/v1`、逻辑 workspace 注册/选择和旧 CLI 已移除，也不提供旧数据自动迁移。

## 1. 支持范围

- HTTP API 只位于 `/api/v2`；WebUI 随 jar 打包。
- MCP 已退役，秘书采用独立凭证接入 HTTP。
- 同一个 workspace 不能被多个 知识库服务进程同时打开。
- SQLite、WAL/SHM 和 Lucene 索引必须位于主机本地文件系统。
- 个人资料模式支持服务凭证和可信代理认证；TLS 由 Caddy 终止，不提供集群或官方容器镜像。

## 2. 环境要求

- JDK 17+；项目编译目标为 Java 17。
- Maven 3.9+。
- 一个不存在或为空、服务账号可读写的 workspace 目录。
- 可选：Ollama 或受支持的远程 LLM 配置。
- 可选：ripgrep、Universal Ctags、Git 的绝对可执行文件路径。

```bash
java -version
mvn -version
```

## 3. 五分钟快速开始

### 构建

```bash
mvn clean package
```

产物是 `target/knowledge-base-1.0.0-SNAPSHOT.jar`，默认入口为 `me.asu.kb.http.SingleWorkspaceHttpServerMain`。

### 配置

Linux/macOS：

```bash
cp config-example.toml config.toml
```

PowerShell：

```powershell
Copy-Item config-example.toml config.toml
```

最小配置：

```toml
workspace_root = "./service-workspace"
port = 8080
llm_provider = "echo"
```

`echo` 适合部署 smoke check，但不提供真实模型回答。

### 启动与验证

```bash
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar --config config.toml
```

另开终端：

```bash
curl http://127.0.0.1:8080/api/v2/health
curl http://127.0.0.1:8080/api/v2/ready
```

- `/health`：进程存活检查。
- `/ready`：完整 composition 就绪时返回 200，否则返回 503。
- 使用页：<http://127.0.0.1:8080/>
- 管理页：<http://127.0.0.1:8080/webui/manage.html>

### 第一次导入与搜索

管理页可以导入文件或目录。HTTP 示例中的 `root` 是 workspace 内的相对目录；先把待导入内容放入 `workspace_root/docs`，再执行：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/ingest/dir \
  -H 'Content-Type: application/json' \
  -d '{"root":"docs"}'

curl -G http://127.0.0.1:8080/api/v2/knowledge/search \
  --data-urlencode 'query=workspace' \
  --data-urlencode 'limit=10'
```

## 4. 配置参考

配置文件是平面 TOML，不支持 `[section]`。支持 `${ENV_NAME}` 展开，但服务不会自动加载 `.env` 或 `env.example`；请先由进程环境提供变量。相对 `workspace_root` 按 TOML 文件所在目录解析，命令行 `--workspace-root` 则按启动进程的当前目录解析。CLI 使用 `--kebab-case`，TOML 使用 `snake_case`；CLI 覆盖同名 TOML，`--config` 只能出现一次。

| TOML / CLI | 默认值 | 说明 |
| --- | --- | --- |
| `workspace_root` / `--workspace-root` | 无 | 必填，必须恰好一个 |
| `port` / `--port` | `8080` | HTTP 端口，1–65535 |
| `llm_provider` / `--llm-provider` | `ollama` | provider；也兼容 TOML 键 `llm` |
| `model` / `--model` | `qwen3:4b` | Ollama 模型 |
| `ollama_url` / `--ollama-url` | `http://localhost:11434/api/generate` | Ollama endpoint |
| `openai_url`、`openai_api_key`、`openai_model` | provider 默认值 | OpenAI-compatible 配置 |
| `gemini_url`、`gemini_api_key`、`gemini_model` | provider 默认值 | Gemini 配置 |
| `groq_url`、`groq_api_key`、`groq_model` | provider 默认值 | Groq 配置 |
| `ripgrep_executable` | 未启用 | ripgrep 绝对路径 |
| `ctags_executable` | 未启用 | Universal Ctags 绝对路径 |
| `git_executable` | 未启用 | Git 绝对路径 |
| `virtual_project_sync_interval` / `--virtual-project-sync-interval` | 未启用 | ISO-8601 fixed delay，`PT1M` 至 `P1D` |
| `virtual_project_sync_concurrency` / `--virtual-project-sync-concurrency` | `4` | 定时同步并发数，1–32 |

外部工具也可使用对应的 CLI 形式 `--ripgrep-executable`、`--ctags-executable` 和 `--git-executable`；启用时必须提供绝对路径，服务不会从 `PATH` 自动发现。

密钥建议使用环境变量，不要提交实际密钥配置：

```toml
openai_api_key = "${OPENAI_API_KEY}"
```

以下旧字段会令启动失败：`workspace`、`workspace_id`、`workspaces`、`server_workspace`、`server_root`、`server_roots`，以及 `--workspace`、`--workspace-id`、`--server-workspace`、`--server-root`。

## 5. Workspace 布局

首次启动只接受不存在或为空的根目录，并创建：

```text
service-workspace/
├── .engineering-knowledge-workspace.json
├── document/
├── state/
│   └── engineering.sqlite
└── index/
    └── engineering/
```

marker 格式为 `engineering-knowledge-single-workspace`、版本 `1`。非空但无 marker、marker 损坏/版本不兼容、旧数据库或索引布局都会被拒绝。不要手工编辑 marker、SQLite、WAL/SHM 或 Lucene 文件。

## 6. 部署

建议分开程序和持久化数据：

```text
/opt/knowledge-base/       # jar 与 config
/var/lib/knowledge-base/   # workspace_root，服务账号独占读写
```

Windows 可使用 `C:\apps\knowledge-base` 与 `D:\data\knowledge-base`。

稳定前台命令：

```bash
java -jar /opt/knowledge-base/knowledge-base-1.0.0-SNAPSHOT.jar \
  --config /opt/knowledge-base/config.toml
```

进程管理器应负责固定工作目录/服务账号、注入密钥、收集输出、发送正常终止信号，并使用 `/api/v2/health` 和 `/api/v2/ready` 探测。必须阻止两个实例使用同一 workspace。

### 网络与安全

HTTP 没有内建认证或 TLS，也没有独立 bind-host 配置。不要直接暴露到不可信网络。远程访问应由操作方通过防火墙、受控网络或带认证/TLS 的反向代理保护。代理应保留 `/api/v2` 和 `/webui`，不要创建 `/api/v1` 转发或注入 workspace header。

### 停止

发送正常终止信号（前台可 `Ctrl+C`）。shutdown hook 会停止接收新操作并关闭 HTTP、应用、SQLite 和索引资源。

## 7. WebUI 与 HTTP API

使用页支持搜索、知识对象、问答、文档和 session；管理页支持状态、导入、索引重建、根目录管理、文档浏览以及虚拟项目的资源/同步状态查看。UI 固定使用 service workspace，没有 workspace 选择器。请求执行中会锁定对应操作，失败、部分结果和 freshness/provenance 会在结果区域或活动区显示。

所有路由以 `/api/v2` 开头：

| 功能 | 路由 |
| --- | --- |
| 状态 | `GET /health`、`GET /ready`、`GET /status` |
| 工程知识 | `GET/POST /knowledge/search`、`GET /knowledge/objects/{id}`、`GET /knowledge/trace/{id}`、`POST /knowledge/context` |
| 导入/索引 | `POST /ingest/file`、`POST /ingest/dir`、`POST /index/rebuild` |
| 根目录 | `GET /roots`、`POST /roots/remove` |
| 文档 | `GET/POST /documents`、`GET/PATCH/DELETE /documents/{id}`、revision 和 trash 路由 |
| 虚拟项目 | `/virtual-projects` 的项目、资源、同步、状态和文件操作路由 |
| 问答/session | `POST /ask`、session CRUD、messages、summary、import/export |
| 维护 | `POST /maintenance/cleanup`、`GET /maintenance/events` |

```bash
curl -X POST http://127.0.0.1:8080/api/v2/knowledge/context \
  -H 'Content-Type: application/json' \
  -d '{"target":"SingleWorkspaceLauncher","intent":"UNDERSTAND","tokenBudget":1200}'
```

请求不得包含 `workspaceId`、`workspace`、`workspace_id`、`X-Workspace-Id` 或 `X-Workspace`，否则返回 HTTP 400 与 `OBSOLETE_WORKSPACE_FIELD`。编辑与 session 细节见[文章编辑说明](article-editing.md)。

### 7.1 虚拟项目文件与 Agent Memory

Agent Memory 使用普通的虚拟项目文件，不使用额外的 Memory 数据库、分类器或生命周期。Agent 通过 HTTP 文件操作维护这些文件；文件仍保存在原始本地路径中，不会被复制到 service workspace。

典型流程是：先把项目目录（或单个文件）作为资源引用加入项目，再在该目录范围内写入 Memory 文件。例如：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/resources \
  -H 'Content-Type: application/json' \
  -d '{"kind":"DIRECTORY","sourcePath":"D:/work/my-project"}'

curl -X POST http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/files \
  -H 'Content-Type: application/json' \
  -d '{"sourcePath":"D:/work/my-project/.agent-memory/notes.md","content":"今天确认了同步边界。\n"}'
```

文件接口位于 `/api/v2/virtual-projects/{projectId}/files`：

- `POST /files` 创建文件；目标已存在时失败。
- `POST /files/append` 追加 UTF-8 文本。
- `POST /files/replace` 替换完整 UTF-8 文本。
- `POST /files/edit` 使用 `oldText` 和 `newText` 做字面编辑；`oldText` 必须恰好出现一次，否则返回冲突且不写入。
- `DELETE /files` 删除项目范围内的普通文件。

每个目标都必须通过路径策略，并且必须是项目的文件引用，或位于项目目录引用的后代路径中。内容上限为 2 MiB；操作成功时返回 `outcome=SUCCEEDED` 和 `requiresSynchronization=true`。写入不会即时重建索引，也不会改变项目资源引用。

要让新增或修改的 Memory 文件进入项目派生索引，写操作成功后显式同步项目：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/sync
curl http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/status
```

也可以配置可选的 `virtual_project_sync_interval`，让固定延迟调度器执行相同的同步操作。同步会重新扫描目录引用，发现新增文件并协调已删除或已修改的文件；只有支持的 UTF-8 文本文件会成为可搜索内容。单个文件不可读或不支持时，项目仍会保留可用结果，同时在同步报告和状态中标记部分失败诊断。

### 7.2 虚拟项目管理、路径策略与项目范围搜索

虚拟项目只保存共享 workspace 中的项目元数据、资源引用和派生索引；文件仍保留在原始本地路径。创建或删除项目、资源引用和派生索引都不会复制、移动或删除源文件。

项目管理接口如下：

- `GET /api/v2/virtual-projects`：列出项目。
- `POST /api/v2/virtual-projects`：创建项目，JSON 体使用 `name` 和可选的 `description`。
- `GET/PATCH/DELETE /api/v2/virtual-projects/{projectId}`：读取、更新或删除项目。
- `GET /api/v2/virtual-projects/{projectId}/resources`：列出资源引用。
- `POST /api/v2/virtual-projects/{projectId}/resources`：增加 `FILE` 或 `DIRECTORY` 引用，体例如 `{"kind":"DIRECTORY","sourcePath":"D:/work/my-project"}`。
- `DELETE /api/v2/virtual-projects/{projectId}/resources/{referenceId}`：移除引用，不删除源文件。

路径策略在增加引用、扫描、同步、读取和写入前统一检查：`workspace_root` 下的路径默认可用；外部路径默认拒绝；禁止规则优先于允许规则；规则按规范化/真实路径和路径边界匹配，不能通过 `..` 或符号链接绕过。拒绝响应使用不包含本地绝对路径的诊断。当前版本没有可供用户配置的外部路径 allow 开关，因此外部引用不应视为受支持部署能力。策略是启动快照，修改受支持的配置后必须重启服务。

显式同步和状态查询：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/sync
curl http://127.0.0.1:8080/api/v2/virtual-projects/{projectId}/status
```

同步会重新展开目录引用，索引支持的 UTF-8 文本/代码文件，并协调新增、修改和删除。状态会返回索引新鲜度、最近同步结果和有界诊断；部分源失败时保留可用结果并标记不完整。可通过 `virtual_project_sync_interval`（`PT1M` 至 `P1D` 的 ISO-8601 fixed delay）和 `virtual_project_sync_concurrency`（1 至 32）启用有界定时同步；同一项目的重叠同步会跳过。

项目范围搜索在 HTTP 查询中增加可选的 `virtualProjectId`：

```bash
curl -G http://127.0.0.1:8080/api/v2/knowledge/search \
  --data-urlencode 'query=同步边界' \
  --data-urlencode 'virtualProjectId={projectId}' \
  --data-urlencode 'limit=10'
```

省略该参数时保持原有 shared-only 搜索；提供有效项目 ID 时合并共享知识和该项目索引，并对项目结果应用确定性偏好。HTTP 结果保留 scope、项目 ID、来源位置、新鲜度和有界诊断。

## 秘书接入与个人资料收集

MCP 入口已退役。秘书使用 HTTP API 1.0；参见 [接入契约](handoffs/personal-secretary-knowledge-handoff.md)。个人资料模式提供上传、笔记、处理进度与受保护阅读，部署方式见 deploy/personal/。

## 9. 日常运维

- liveness：`GET /api/v2/health`
- readiness：`GET /api/v2/ready`
- 详细状态：`GET /api/v2/status`

readiness 失败时检查 `state`、`components`、`queueDepth` 和 `freshness`。

重建索引：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/index/rebuild \
  -H 'Content-Type: application/json' -d '{}'
```

SQLite 是业务状态权威，Lucene 可重建。维护清理使用 `POST /api/v2/maintenance/cleanup`；根目录移除默认用 detach，purge 必须明确确认。

## 10. 备份、恢复、升级与回滚

### 备份

1. 正常停止服务，确认没有 知识库服务进程打开 workspace。
2. 复制完整 `workspace_root`，包括 marker、`document/`、`state/`、WAL/SHM 和 `index/`。
3. 校验完整性与权限。

不要只复制 SQLite，也不要在运行中复制部分目录。

### 恢复

恢复完整备份到独占目录，检查 marker/权限，配置 `workspace_root`，启动后检查 health、ready、status、文档和搜索。

### 升级与回滚

升级前记录当前 jar/config/Java 版本，停止服务并完成整 workspace 备份；替换 jar 后验证状态端点和关键工作流。回滚时停止新版本，恢复匹配的完整 workspace 备份和旧 jar。当前没有 down-migration。

## 11. 故障排查

| 现象 | 处理 |
| --- | --- |
| 必须配置一个 workspace | 只保留一个 `workspace_root` 或 `--workspace-root` |
| `obsolete workspace field` | 删除旧 `workspace*`/`server_root*`，改用 `workspace_root` |
| TOML 解析失败 | 使用平面 `key = value`，检查 section、重复 key 和环境变量 |
| 端口失败 | 确认值为 1–65535，并检查端口占用 |
| health 正常、ready 503 | 查看 status，检查权限、SQLite 和 projection/index recovery |
| workspace 被拒绝 | 使用空目录或带有效 marker 的当前格式目录 |
| LLM 失败 | 检查 provider/URL/model/key；先用 `echo` 验证其他路径 |
| code search/symbol 不可用 | 配置 ripgrep/Ctags 绝对路径；不会从 PATH 自动发现 |
| 搜索落后 | 查看 projection freshness，必要时显式 rebuild |

## 12. 相关文档

- [产品介绍与答疑手册](introduction-faq.zh.md)
- [单工作区服务说明](single-workspace-service.md)
- [文章编辑说明](article-editing.md)
- [配置示例](../config-example.toml)
