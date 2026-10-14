# Single-Workspace Public Contract Decisions

This document is the evidence for Work Item 0.2. It freezes the public choices required before launcher or handler implementation.

## HTTP version and service probes

- The replacement API prefix is `/api/v2`. The new launcher does not mount new behavior on `/api/v1` and does not redirect or alias `/api/v1`.
- `GET /api/v2/health` is a process liveness probe. While the HTTP listener can answer, it returns HTTP 200 with `schemaVersion`, `alive: true`, and `state`.
- `GET /api/v2/ready` is the readiness probe. It returns HTTP 200 only when the complete application composition accepts work, and HTTP 503 for `starting`, `degraded`, `closing`, or `failed`.
- `GET /api/v2/status` carries the fuller service/workspace status. The readiness response intentionally omits the absolute workspace root because this version has no authentication boundary.

The readiness payload has these stable fields:

```json
{
  "schemaVersion": "1",
  "ready": true,
  "state": "ready",
  "reason": null,
  "components": {
    "store": "ready",
    "index": "ready",
    "application": "ready"
  },
  "queueDepth": 0,
  "freshness": {
    "businessCheckpoint": null,
    "projectionCheckpoint": null,
    "lastSuccessfulProjectionCommitAt": null
  }
}
```

`schemaVersion`, `ready`, `state`, `components`, `queueDepth`, and `freshness` are always present. `reason` is either `null` or a bounded operator-safe message. Component values use `starting`, `ready`, `degraded`, `closing`, or `failed`. Optional external tools are reported by `/api/v2/status`; their absence does not make document-only service composition unready.

## One-root configuration

The canonical command-line key is `--workspace-root <path>` and the canonical TOML key is `workspace_root = "<path>"`. Exactly one non-blank value is required for the new HTTP and MCP launchers. The root is converted to an absolute normalized path once; configuration validation completes before marker creation, SQLite connection, Lucene open, executor creation, or transport startup.

Environment substitution already supported by the configuration loader may supply the TOML value. There is no implicit home-directory default for the new launcher and no comma-separated or repeated root form.

The internal engineering workspace ID is the constant `service`; it is never configurable or emitted as a routing field.

## Workspace format marker

The marker is `<workspace-root>/.engineering-knowledge-workspace.json`, UTF-8 JSON with exactly these required identity fields:

```json
{
  "format": "engineering-knowledge-single-workspace",
  "version": 1
}
```

Additional fields are not part of version 1 and are rejected. Initialization is allowed only when the root does not exist or is an empty directory. The implementation creates the root if needed and publishes the complete marker atomically before opening mutable Store/index resources. An existing non-empty directory without the marker, malformed marker JSON, another format, or a version other than integer `1` fails startup without creating, deleting, migrating, or opening other state.

## Obsolete configuration rejection

The new launcher rejects these legacy CLI keys before resource creation:

- `--workspace`
- `--workspace-id`
- `--server-workspace`
- `--server-root`

It rejects these legacy TOML keys before resource creation:

- `workspace`
- `workspace_id`
- `workspaces`
- `server_workspace`
- `server_root`
- `server_roots`

The diagnostic names the obsolete field and directs the operator to `workspace_root` / `--workspace-root`. Supplying both the canonical key and any obsolete key is still an error; the obsolete value is never ignored.

## Obsolete request-field rejection

Every `/api/v2` handler rejects caller-selected workspace routing in query parameters, JSON object fields, multipart form fields, or recognized routing headers. The obsolete names are `workspaceId`, `workspace`, and `workspace_id`; recognized headers are `X-Workspace-Id` and `X-Workspace` (case-insensitive).

The response is HTTP 400 and follows this stable shape:

```json
{
  "schemaVersion": "1",
  "ok": false,
  "code": "OBSOLETE_WORKSPACE_FIELD",
  "message": "workspace routing is not supported",
  "field": "workspaceId"
}
```

Validation occurs before invoking an application operation. The server does not ignore the field, choose a default based on it, perform fallback, or echo an internal workspace ID. `/api/v1` is outside the new contract and remains a bypassed legacy implementation until the later cleanup decision.
