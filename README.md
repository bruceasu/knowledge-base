# knowledge-base

[中文](README.zh.md)

`knowledge-base` is a self-hosted personal knowledge service with a single local workspace, document ingestion, SQLite-backed projections, Lucene BM25 retrieval, evidence-aware context building, a bundled WebUI, and a personal secretary HTTP API.

## Quick start

Requirements: JDK 17+, Maven 3.9+, and an empty writable workspace directory.

```bash
mvn clean package
cp config-example.toml config.toml
java -jar target/knowledge-base-1.0.0-SNAPSHOT.jar --config config.toml
```

Minimum configuration:

```toml
workspace_root = "./service-workspace"
port = 8080
llm_provider = "echo"
```

Verify and open:

```bash
curl http://127.0.0.1:8080/api/v2/health
curl http://127.0.0.1:8080/api/v2/ready
```

- WebUI: <http://127.0.0.1:8080/>
- Management: <http://127.0.0.1:8080/webui/manage.html>

Import a directory and search it through the workspace-free API:

```bash
curl -X POST http://127.0.0.1:8080/api/v2/ingest/dir \
  -H 'Content-Type: application/json' \
  -d '{"root":"docs"}'
curl -G http://127.0.0.1:8080/api/v2/knowledge/search \
  --data-urlencode 'query=workspace' \
  --data-urlencode 'limit=10'
```

The ingestion path must be inside the configured workspace. The [Chinese user
and deployment guide](docs/user-guide.zh.md) covers configuration, daily usage,
personal collection, backup, upgrade, and troubleshooting.

## Runtime boundaries

- One process owns exactly one configured `workspace_root`; never open one workspace from multiple processes.
- HTTP is available only under `/api/v2`. Legacy `/api/v1`, logical workspace routing, and the old CLI are removed.
- The WebUI is bundled in the executable jar.
- MCP is retired; secretary access uses the authenticated HTTP contract.
- HTTP binds to loopback. Personal mode requires application credentials plus same-host Caddy/Authelia; TLS terminates at Caddy.
- Keep SQLite, WAL/SHM files, and Lucene indexes on a host-local filesystem.

## 秘书接入与个人资料收集

MCP 入口已退役。秘书使用 HTTP API 1.0；参见 [接入契约](docs/handoffs/personal-secretary-knowledge-handoff.md)。个人资料模式提供上传、笔记、处理进度与受保护阅读，部署方式见 deploy/personal/。

## Documentation

- [Product introduction and FAQ (Chinese)](docs/introduction-faq.zh.md)
- [User and deployment guide (Chinese)](docs/user-guide.zh.md)
- [Single-workspace service guide](docs/single-workspace-service.md)
- [Article editing](docs/article-editing.md)
- [Configuration example](config-example.toml)
- [Environment-variable example](env.example) (values are referenced from TOML; the service does not load `.env` automatically)
