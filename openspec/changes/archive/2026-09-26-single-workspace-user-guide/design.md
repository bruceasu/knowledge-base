## Context

The repository currently has bilingual README files, a focused single-workspace service note, topic documents, and an example TOML. They contain useful facts but do not form a complete operator journey, and duplicated command/configuration snippets can drift. The supported product has one HTTP/WebUI launcher, one explicit local stdio MCP launcher, one required workspace root, and no legacy `/api/v1` or logical-workspace surface.

## Goals / Non-Goals

**Goals:**

- Give a new user one canonical Chinese guide from prerequisites to a working service in five minutes.
- Cover development startup, packaged deployment, configuration, WebUI, API, MCP, data protection, health/readiness, shutdown, and troubleshooting.
- Derive every command, option, endpoint, and configuration key from current source/config/spec evidence.
- Keep README files short and navigational while preserving copyable minimum startup commands.

**Non-Goals:**

- Changing application behavior, configuration parsing, API/MCP schemas, persistence, authentication, or deployment architecture.
- Documenting the removed CLI, `/api/v1`, logical workspace selection, remote MCP, containers, clustering, or unsupported service managers as established product features.
- Duplicating every endpoint schema already represented by source and focused domain documentation.

## Decisions

### Use one canonical Chinese operations guide

Create `docs/user-guide.zh.md` as the detailed manual. `README.zh.md` links to it as the primary path; `README.md` retains a compact English introduction and links to the Chinese manual plus the existing focused service note. This limits duplicated long-form procedures while keeping both repository entry pages useful.

Alternative considered: duplicate a complete guide in both languages. Rejected because the current task prioritizes an accurate manual and duplicated operational commands are likely to drift.

### Organize by user journey

The guide follows prerequisites, quick start, configuration, deployment, use, operations, backup/upgrade, troubleshooting, and security boundaries. Reference tables support the journey rather than leading it.

Alternative considered: organize by Java package or capability history. Rejected because operators need task completion, not implementation chronology.

### Treat source and active specs as authoritative

Launch commands come from Maven packaging and production main classes; keys and precedence come from `SingleWorkspaceServiceConfig`/`SingleWorkspaceLauncher`; endpoints come from `SingleWorkspaceHttpApp`; MCP tools come from the active MCP spec and schemas. Unsupported behavior is explicitly labeled rather than inferred.

### Keep deployment examples topology-neutral

Document foreground Java deployment, filesystem permissions, process supervision principles, reverse-proxy considerations, health probes, graceful shutdown, and backup boundaries. Do not claim built-in TLS, authentication, remote MCP, HA, container images, or a specific init system.

## Risks / Trade-offs

- **Command drift** → Keep root README examples minimal and link to one canonical guide; statically compare all examples with source/config.
- **Unsafe exposure** → State that the HTTP service has no documented authentication boundary and should bind to a trusted interface or sit behind an operator-controlled access layer.
- **Data-loss guidance** → Require a stopped/quiescent service for filesystem-level backup and identify the whole workspace root as the backup unit.
- **JDK ambiguity** → Document Java 17 as the compilation target and note that the verified local run used JDK 25 without making JDK 25 mandatory.
- **MCP confusion** → Clearly state that MCP is local stdio only, uses a separate explicit main class, and must not be presented as a network listener.

## Migration Plan

1. Add the canonical guide using current commands and contracts.
2. Update README entry points and the focused service note to link to it.
3. Reconcile the example TOML comments without changing parsed keys.
4. Perform static link, command, config-key, endpoint, and legacy-token checks.

Rollback is documentation-only: restore the previous Markdown/example files. No runtime or data rollback is required.

## Open Questions

None. Product-specific deployment integrations can be documented in later changes once they exist.
