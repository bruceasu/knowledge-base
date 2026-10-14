## 1. Source-of-truth inventory

- [x] 1.1 Record the supported build/runtime prerequisites, artifact name, launchers, CLI/TOML precedence, canonical keys, workspace layout, health probes, WebUI path, `/api/v2` route groups, and six MCP tools from current source and active specs.
  - Evidence: the guide derives Java/Maven requirements and artifact naming from `pom.xml`, configuration and precedence from `SingleWorkspaceLauncher`/`SingleWorkspaceServiceConfig`, routes from `SingleWorkspaceHttpApp`, layout from `SingleWorkspaceLayout`/`SingleWorkspaceKnowledgeService`, and tools from `KnowledgeAgentToolSchemas` plus the active MCP spec.
- [x] 1.2 Identify and resolve contradictions or obsolete commands across both README files, `docs/single-workspace-service.md`, and `config-example.toml` without changing runtime behavior.
  - Evidence: replaced unreadable/duplicated entry text with UTF-8 documentation, one canonical guide, and consistent single-workspace commands; no production source was changed.

## 2. Canonical user guide

- [x] 2.1 Add `docs/user-guide.zh.md` with a copyable five-minute quick start, configuration reference, build and foreground launch instructions, and first health/readiness/WebUI checks.
- [x] 2.2 Document deployment boundaries, filesystem ownership, process supervision, reverse-proxy/access considerations, graceful shutdown, backup, restore, upgrade, and rollback guidance.
- [x] 2.3 Document WebUI workflows, representative `/api/v2` requests, local stdio MCP integration and six tools, workspace data layout, routine operations, and troubleshooting.

## 3. Entry points and examples

- [x] 3.1 Update `README.zh.md` and `README.md` to provide concise supported quick-start commands and links to the canonical guide, focused service note, and example configuration.
- [x] 3.2 Reconcile `docs/single-workspace-service.md` and `config-example.toml` with the canonical guide while preserving supported keys and behavior.

## 4. Verification and closeout

- [x] 4.1 Statically verify internal Markdown links, artifact/main-class names, CLI/TOML keys, ports, `/api/v2` routes, WebUI path, MCP tool names, and absence of active `/api/v1` or logical-workspace usage instructions.
  - Evidence: one combined read-only check found no broken relative link, missing required fact, or executable legacy command. `git diff --check` reported no whitespace error; only existing line-ending conversion warnings were emitted.
- [x] 4.2 Update TODO, Verification, and remaining `%%` notes; record limitations and confirm no Java, dependency, schema, authentication, user-data, deployment-topology, or archived-history change.
  - Evidence: no `%%` note remains. Changes are limited to Markdown, comments in `config-example.toml`, and this OpenSpec change. No Java, dependency, schema, authentication, user data, deployment topology, or archive content changed. Commands were statically checked but not executed in this documentation-only task.

## TODO

- [x] A new user can reach a ready service and bundled WebUI by following one copyable quick-start path.
- [x] An operator can deploy, secure externally, monitor, stop, back up, restore, and upgrade the service using documented boundaries.
- [x] Browser, HTTP, and local stdio MCP usage is documented without exposing removed or unsupported product surfaces.

## Verification

- [x] All commands, keys, endpoints, tool names, and paths are backed by current source, configuration, or active specs.
- [x] Both README entry points lead to the canonical guide and contain no contradictory legacy instructions.

## 5. Product introduction and update FAQ

- [x] 5.1 Add a presentation-oriented Chinese introduction covering product value, supported and unsupported scenarios, architecture, and reusable 30-second/3-minute explanations.
- [x] 5.2 Explain the information-update lifecycle, visibility and freshness semantics, unchanged-file optimization, deletion, conflict handling, projection recovery, rebuild decisions, and operational FAQ using current source and active-spec evidence.
- [x] 5.3 Link the introduction/FAQ from both README files and the canonical user guide.

### Verification

- [x] Update guidance distinguishes explicit ingest/sync, business commit, projection publication, retry, and rebuild without claiming an automatic file watcher.
- [x] FAQ preserves the single-process/single-workspace, local stdio MCP, external-authentication, and complete-workspace backup boundaries.
