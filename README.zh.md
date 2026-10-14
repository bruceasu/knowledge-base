# knowledge-base

[English](README.md)

`knowledge-base` 是本地单工作区工程知识服务，提供文档摄取、SQLite 知识投影、Lucene BM25 检索、证据追踪、上下文构建、内置 WebUI 和六个只读 stdio MCP 工具。

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

完整步骤、Linux/Windows 部署、配置、API、MCP、备份升级与排障见 [用户与部署手册](docs/user-guide.zh.md)。

## 核心约束

- 一个进程只拥有一个 `workspace_root`，同一目录不能被多个进程同时打开。
- API 只提供 `/api/v2`；旧 `/api/v1`、逻辑 workspace 路由和旧 CLI 已移除。
- WebUI 随 jar 打包，不需要独立前端部署。
- MCP 只支持本地 stdio，不监听网络端口。
- HTTP 没有内建认证或 TLS，不要直接暴露到不可信网络。
- SQLite、WAL/SHM 和 Lucene 索引应保存在主机本地文件系统。

## 本地 stdio MCP

```bash
java -cp target/knowledge-base-1.0.0-SNAPSHOT.jar \
  me.asu.kb.mcp.KnowledgeMcpServerMain \
  --config config.toml
```

工具：`knowledge_search`、`knowledge_get`、`code_symbol`、`code_search`、`trace`、`build_context`。

## 文档

- [产品介绍与答疑手册](docs/introduction-faq.zh.md)
- [用户与部署手册](docs/user-guide.zh.md)
- [单工作区服务说明](docs/single-workspace-service.md)
- [文章编辑说明](docs/article-editing.md)
- [配置示例](config-example.toml)
- [环境变量示例](env.example)（变量需由 TOML 中的 `${ENV_NAME}` 引用，服务不会自动加载 `.env`）
