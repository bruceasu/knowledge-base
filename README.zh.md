# knowledge-base

[English](README.md)

`knowledge-base` 是用户自行掌控的个人知识库，采用单工作区并保留工程知识检索能力，提供文档摄取、SQLite 知识投影、Lucene BM25 检索、证据追踪、上下文构建、内置 WebUI 和个人秘书 HTTP API。

默认配置目录为 `~/.config/knowledge/`：程序自动读取其中的 `config.toml` 和 `env`（若文件存在）。也可通过 `--config` 或 `KB_CONFIG` 指定 TOML 文件。

## 快速开始

要求 JDK 17+、Maven 3.9+，以及一个可写的空 workspace。

```bash
mvn clean package
```

复制并编辑配置：

```powershell
Copy-Item config-example.toml config.toml
```

```toml
workspace_root = "./service-workspace"
port = 8080
llm_provider = "echo"
```

启动 HTTP/WebUI：

```bash
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar --config config.toml
```

验证并打开界面：

```bash
curl http://127.0.0.1:8080/api/v2/health
curl http://127.0.0.1:8080/api/v2/ready
```

- 使用页：<http://127.0.0.1:8080/>
- 管理页：<http://127.0.0.1:8080/webui/manage.html>

首次导入与搜索（`root` 必须位于配置的 `workspace_root` 内）：

```bash
curl -X POST http://127.0.0.1:8080/api/v2/ingest/dir \
  -H 'Content-Type: application/json' \
  -d '{"root":"docs"}'
curl -G http://127.0.0.1:8080/api/v2/knowledge/search \
  --data-urlencode 'query=workspace' \
  --data-urlencode 'limit=10'
```

完整步骤、Linux/Windows 部署、配置、API、个人资料收集、备份升级与排障见 [用户与部署手册](docs/user-guide.zh.md)。

## 启动与打包脚本

`scripts/start-maven.sh` / `scripts/start-maven.ps1` 通过 Maven 编译并直接启动应用入口，启动器自动读取 `~/.config/knowledge/config.toml` 和 `~/.config/knowledge/env`；可用 `KB_CONFIG` 选择其他配置文件。直接启动 Jar 时也可使用 `--config`。

```bash
bash scripts/start-maven.sh
KB_CONFIG=config-dev.toml bash scripts/start-maven.sh
bash scripts/package-with-jre.sh
bash scripts/package-without-jre.sh
```

PowerShell 脚本：`scripts\start-maven.ps1`、`scripts\package-with-jre.ps1`、`scripts\package-without-jre.ps1`。需要更换 Maven 启动配置时设置 `$env:KB_CONFIG` 后运行脚本。打包 ZIP 输出到 `target/distributions/`：`package-with-jre` 使用 JDK 17+ 的 `jdeps`/`jlink` 生成精简运行时，包只能运行于打包时相同的操作系统和架构；`package-without-jre` 不带运行时，目标机器需安装 Java 17+。两种包都包含 Jar、启动脚本、配置示例和使用/运维文档。

## 核心约束

- 一个进程只拥有一个 `workspace_root`，同一目录不能被多个进程同时打开。
- API 只提供 `/api/v2`；旧 `/api/v1`、逻辑 workspace 路由和旧 CLI 已移除。
- WebUI 随 jar 打包，不需要独立前端部署。
- MCP 已退役，秘书通过认证的 HTTP 契约接入。
- HTTP 没有内建认证或 TLS，不要直接暴露到不可信网络。
- SQLite、WAL/SHM 和 Lucene 索引应保存在主机本地文件系统。

## 秘书接入与个人资料收集

MCP 入口已退役。秘书使用 HTTP API 1.0；参见 [接入契约](docs/handoffs/personal-secretary-knowledge-handoff.md)。个人资料模式提供上传、笔记、处理进度与受保护阅读，部署方式见 deploy/personal/。

## 文档

- [产品介绍与答疑手册](docs/introduction-faq.zh.md)
- [用户与部署手册](docs/user-guide.zh.md)
- [单工作区服务说明](docs/single-workspace-service.md)
- [文章编辑说明](docs/article-editing.md)
- [配置示例](config-example.toml)
- [环境变量示例](env.example)（复制到 `~/.config/knowledge/env`；系统环境变量优先）
