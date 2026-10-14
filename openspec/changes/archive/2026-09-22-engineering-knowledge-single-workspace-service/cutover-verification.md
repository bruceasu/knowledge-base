## Work Item 5.3 focused cutover verification

Authorized and executed on 2026-09-22 with Java 21.0.8 and Maven 3.9.9 in
offline mode. All service fixtures use JUnit temporary directories; no
repository workspace data or legacy persisted state was opened.

### Final focused smoke command

```powershell
$env:JAVA_HOME='C:\green\jdk-21.0.8'
& 'C:\green\apache-maven-3.9.9\bin\mvn.cmd' -o '-Dtest=SingleWorkspaceServiceConfigTest,SingleWorkspaceLayoutTest,KnowledgeBaseDocumentApplicationTest,SingleWorkspaceLauncherTest,ServiceOperationCoordinatorTest,SingleWorkspaceKnowledgeServiceTest,SingleWorkspaceHttpContractTest,SingleWorkspaceHttpIntegrationTest,SingleWorkspaceWebUiContractTest,AgentToolBoundaryTest,KnowledgeAgentToolSchemasTest,KnowledgeMcpToolHandlersTest,KnowledgeMcpServerTest,KnowledgeMcpServerMainTest,KnowledgeMcpStdioHarnessTest' test
```

Result: **PASS** — 42 tests run, 0 failures, 0 errors, 0 skipped. Maven total
time was 9.434 seconds.

The suite verifies fresh marker/configuration handling, one production resource
graph, document operations, readiness and shutdown, HTTP v2 ingestion through
retrieval/read/trace/context, bundled UI assets, MCP startup and all six tools,
obsolete workspace-field rejection, explicit optional-tool degradation,
deadline/thread interruption, protocol-only output, and continued service after
a failed MCP call.

### Compile command

```powershell
$env:JAVA_HOME='C:\green\jdk-21.0.8'
& 'C:\green\apache-maven-3.9.9\bin\mvn.cmd' -o '-DskipTests' compile
```

Result: **PASS** — `BUILD SUCCESS`, Maven total time 0.714 seconds.

### Recovery and rerun evidence

The first focused run reused stale incremental Maven outputs: the generated
`KnowledgeBaseDocumentApplication$1` class was absent while Maven reported both
main and test classes up to date. This produced secondary document/HTTP failures
and stale MCP harness behavior. The bounded recovery command removed only Maven
generated `target` content and rebuilt the same selected suite:

```powershell
$env:JAVA_HOME='C:\green\jdk-21.0.8'
& 'C:\green\apache-maven-3.9.9\bin\mvn.cmd' -o clean '-Dtest=SingleWorkspaceServiceConfigTest,SingleWorkspaceLayoutTest,KnowledgeBaseDocumentApplicationTest,SingleWorkspaceLauncherTest,ServiceOperationCoordinatorTest,SingleWorkspaceKnowledgeServiceTest,SingleWorkspaceHttpContractTest,SingleWorkspaceHttpIntegrationTest,SingleWorkspaceWebUiContractTest,AgentToolBoundaryTest,KnowledgeAgentToolSchemasTest,KnowledgeMcpToolHandlersTest,KnowledgeMcpServerTest,KnowledgeMcpServerMainTest,KnowledgeMcpStdioHarnessTest' test
```

After the clean rebuild, runtime/HTTP/UI tests passed and two MCP harness
assertions exposed SDK 2.0.1 response details. The harness was corrected to
assert schema rejection as `result.isError=true` without facade invocation and
to distinguish hybrid `SOURCE_FAILED` Ctags degradation from direct
`TOOL_UNAVAILABLE` ripgrep degradation. The focused MCP harness then passed 4/4,
followed by the final 42/42 suite above.

### Known limitations and non-blocking warnings

- Optional ripgrep/Ctags executables are intentionally omitted; the suite
  verifies bounded degraded diagnostics rather than successful native-tool
  execution.
- SDK 2.0.1 protocol cancellation remains best-effort; deadline and worker
  thread interruption are verified.
- This is the designed focused service suite, not a repository-wide test,
  package, performance, remote-transport, authentication, deployment, or
  multi-process/shared-filesystem test.
- Maven reports the existing missing `development` profile warning. Runtime
  emits existing SLF4J NOP and Lucene Vector API advisory warnings. None affected
  the result.
- Recovery remains executable-code rollback plus restoration of a separately
  backed-up whole new-format workspace. No down-migration or legacy import is
  promised.
