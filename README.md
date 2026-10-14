# knowledge-base

[中文](README.zh.md)

`knowledge-base` is a local, single-workspace engineering knowledge service with document ingestion, SQLite-backed projections, Lucene BM25 retrieval, evidence-aware context building, a bundled WebUI, and six read-only stdio MCP tools.

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
MCP, backup, upgrade, and troubleshooting.

## Runtime boundaries

- One process owns exactly one configured `workspace_root`; never open one workspace from multiple processes.
- HTTP is available only under `/api/v2`. Legacy `/api/v1`, logical workspace routing, and the old CLI are removed.
- The WebUI is bundled in the executable jar.
- MCP is local stdio only; it does not open a network listener.
- HTTP has no built-in authentication or TLS. Do not expose it directly to an untrusted network.
- Keep SQLite, WAL/SHM files, and Lucene indexes on a host-local filesystem.

## Local stdio MCP

```bash
java -cp target/knowledge-base-1.0.0-SNAPSHOT.jar \
  me.asu.kb.mcp.KnowledgeMcpServerMain \
  --config config.toml
```

Tools: `knowledge_search`, `knowledge_get`, `code_symbol`, `code_search`, `trace`, and `build_context`.

## Documentation

- [Product introduction and FAQ (Chinese)](docs/introduction-faq.zh.md)
- [User and deployment guide (Chinese)](docs/user-guide.zh.md)
- [Single-workspace service guide](docs/single-workspace-service.md)
- [Article editing](docs/article-editing.md)
- [Configuration example](config-example.toml)
- [Environment-variable example](env.example) (values are referenced from TOML; the service does not load `.env` automatically)
