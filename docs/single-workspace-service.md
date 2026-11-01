# 单工作区服务运行说明

新用户请先阅读[用户与部署手册](user-guide.zh.md)。本文只记录稳定的运行时边界和配置合同。

## 运行模型

- 一个进程拥有一个 `workspace_root`。
- HTTP/WebUI 默认入口是 `me.asu.kb.http.SingleWorkspaceHttpServerMain`。
- MCP 显式入口已移除；使用 `/api/v2/secretary` 契约。
- 两个入口各自打开完整 composition，不能同时指向同一个 workspace。
- SQLite 与 Lucene 构造、生命周期和关闭由 application/runtime 层负责，不属于 transport。

## 配置合同

可通过平面 TOML 和 CLI 配置。CLI 覆盖同名 TOML：

```toml
workspace_root = "./service-workspace"
port = 8080
llm_provider = "echo"
ollama_url = "http://localhost:11434/api/generate"
model = "qwen3:4b"
```

```bash
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar --config config.toml
```

或：

```bash
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar \
  --workspace-root ./service-workspace \
  --port 8080 \
  --llm-provider echo
```

开发验证可从仓库根目录运行 `bash scripts/start-maven.sh`；脚本通过 Maven Exec 插件启动应用入口，启动器自动读取 `~/.config/knowledge/config.toml`。Windows PowerShell 使用 `scripts/start-maven.ps1`。若要选择其他配置文件，可设置 `KB_CONFIG` 或显式传入 `--config`。部署包解压后用包内 `scripts/start-jar.sh` 或 `scripts/start-jar.ps1` 启动；它会优先使用包内 `jre/`，否则从 `JAVA_HOME` 或 `PATH` 查找 Java。

通过 `scripts/package-with-jre.sh` / `.ps1` 生成带精简 JRE 的平台专用 ZIP；通过 `scripts/package-without-jre.sh` / `.ps1` 生成不带 JRE 的 ZIP。产物写入 `target/distributions/`。带 JRE 版本必须在目标操作系统和架构上分别打包；不带 JRE 的版本要求目标主机安装 Java 17+。

`workspace_root` 没有程序默认值，且必须恰好配置一个。未指定配置路径时，启动器自动读取 `~/.config/knowledge/config.toml`；`--config` 优先于 `KB_CONFIG`，后者优先于自动路径。启动器同时读取 `~/.config/knowledge/env`，系统环境变量同名值优先于 env 文件值。配置项环境变量使用 `KB_` 前缀（如 `KB_WORKSPACE_ROOT`、`KB_PORT`），TOML 的 `${ENV_NAME}` 展开也能读取系统环境和 env 文件变量。总体优先级为 CLI > 系统环境 > env 文件 > TOML > 程序默认值。相对 TOML 中的 root 按配置文件目录解析；相对环境变量和 CLI root 按当前目录解析。旧 `workspace*`、`server_workspace`、`server_root*` 字段会直接失败。

外部工具 `ripgrep_executable`、`ctags_executable`、`git_executable` 启用时必须是绝对路径；服务不会从环境 `PATH` 自动发现。可选的虚拟项目定时同步配置为 `virtual_project_sync_interval`（`PT1M` 至 `P1D`）和 `virtual_project_sync_concurrency`（1 至 32），CLI 形式分别为 `--virtual-project-sync-interval` 和 `--virtual-project-sync-concurrency`。

## Workspace 合同

首次启动只接受不存在或为空的目录，并创建：

```text
.engineering-knowledge-workspace.json
document/
state/engineering.sqlite
index/engineering/
```

marker 格式为 `engineering-knowledge-single-workspace`、版本 `1`。非空无 marker、marker 损坏/不兼容、旧数据库或旧索引布局会在打开可变资源前失败。

## HTTP/WebUI

```bash
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar --config config.toml
```

- WebUI：`http://127.0.0.1:8080/`
- 管理页：`http://127.0.0.1:8080/webui/manage.html`
- liveness：`GET /api/v2/health`
- readiness：`GET /api/v2/ready`
- status：`GET /api/v2/status`
- 虚拟项目：`/api/v2/virtual-projects`，包括项目管理、资源引用、显式同步、状态和受路径策略约束的文件操作。

所有业务 API 都位于 `/api/v2`。不存在 `/api/v1` alias/redirect，也不接受 workspace routing 字段或 header。

## 秘书接入与个人资料收集

MCP 入口已退役。秘书使用 HTTP API 1.0；参见 [接入契约](handoffs/personal-secretary-knowledge-handoff.md)。个人资料模式提供上传、笔记、处理进度与受保护阅读，部署方式见 deploy/personal/。

## 数据与运维边界

- workspace 必须位于主机本地文件系统，且只能由一个进程打开。
- 后端绑定 loopback；个人资料模式使用应用鉴权和 Caddy/Authelia，不能直连公开端口。
- 备份前正常停止服务，并复制完整 workspace，而不是只复制 SQLite。
- 正常终止会拒绝新操作并按顺序关闭 HTTP、应用、Store 和索引。

## 日常检查与处置

运维探测按 `health → ready → status` 执行：

1. `GET /api/v2/health` 检查进程是否存活。
2. `GET /api/v2/ready` 检查服务是否可以处理完整请求；非 200 时读取 `GET /api/v2/status`。
3. 在 status 中检查总体状态、组件状态、队列深度、索引 freshness 和有界诊断。health 正常但 ready 失败时，优先检查 workspace 权限、SQLite 打开/恢复情况和 projection/index 状态；不要仅通过重启掩盖持续故障。

维护操作的范围需与故障范围匹配：

- 虚拟项目索引过期：对对应项目执行显式 sync，再读取该项目 status；不要因此重建共享索引。
- 共享检索投影损坏或需要重建：执行 `POST /api/v2/index/rebuild`，完成后重新检查 ready 和 status。
- 清理维护数据或 purge 根目录：先确认目标和影响；根目录 detach 保留历史数据，purge 会请求删除关联数据并要求显式确认。

例如检查端点：

```bash
curl -i http://127.0.0.1:8080/api/v2/health
curl -i http://127.0.0.1:8080/api/v2/ready
curl -s http://127.0.0.1:8080/api/v2/status
```

备份、恢复、升级和回滚步骤见[用户与部署手册](user-guide.zh.md)“备份、恢复、升级与回滚”。任何 workspace 复制或恢复操作都应确保没有其他进程同时打开该目录。

完整部署、API 示例、备份恢复、升级回滚和排障流程见[用户与部署手册](user-guide.zh.md)。
