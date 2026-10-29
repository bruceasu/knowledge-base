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

`workspace_root` 没有默认值，且必须恰好配置一个。配置文件支持 `${ENV_NAME}` 展开；相对 root 按 TOML 文件目录解析。CLI 值覆盖同名 TOML，CLI 使用 kebab-case，例如 `--workspace-root`、`--llm-provider`。服务不会自动加载 `.env`，只能展开启动环境中已经存在的 `${ENV_NAME}`。旧 `workspace*`、`server_workspace`、`server_root*` 字段会直接失败。

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

完整部署、API 示例、备份恢复、升级回滚和排障流程见[用户与部署手册](user-guide.zh.md)。
