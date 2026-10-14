package me.asu.kb.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.ImmutableEngineeringWorkspaceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentToolBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void decodesAValidRequestAndBindsTheImplicitServiceWorkspace() throws Exception {
    AgentToolRequestValidator validator = validator();
    ValidatedAgentToolRequest validated =
        validator.validate(
            "knowledge_search",
            Map.of("query", "facade", "resultLimit", 20),
            80,
            AgentCallDeadline.start());

    AgentToolValues.KnowledgeSearchRequest request =
        validated.requestAs(AgentToolValues.KnowledgeSearchRequest.class);
    assertEquals("facade", request.query());
    assertEquals(20, request.resultLimit());
    assertEquals("repo", validated.workspace().id());
  }

  @Test
  void acceptsAnExistingVirtualProjectSelectorWithoutChangingTheWorkspaceBinding() throws Exception {
    AgentToolRequestValidator validator =
        new AgentToolRequestValidator(catalog(), projectId -> "project-a".equals(projectId));

    ValidatedAgentToolRequest validated =
        validator.validate(
            "knowledge_search",
            Map.of("query", "facade", "virtualProjectId", "project-a"),
            80,
            AgentCallDeadline.start());

    AgentToolValues.KnowledgeSearchRequest request =
        validated.requestAs(AgentToolValues.KnowledgeSearchRequest.class);
    assertEquals("repo", request.workspaceId());
    assertEquals("project-a", request.virtualProjectId());
  }

  @Test
  void rejectsUnknownVirtualProjectSelectorsWithSanitizedNotFoundStatus() {
    AgentToolRequestValidator validator =
        new AgentToolRequestValidator(catalog(), projectId -> false);

    AgentRequestException failure =
        assertThrows(
            AgentRequestException.class,
            () ->
                validator.validate(
                    "knowledge_search",
                    Map.of("query", "facade", "virtualProjectId", "missing-project"),
                    80,
                    AgentCallDeadline.start()));

    assertEquals(AgentToolValues.Status.NOT_FOUND, failure.status());
    assertEquals("VIRTUAL_PROJECT_NOT_FOUND", failure.code());
    assertFalse(failure.getMessage().contains("missing-project"));
    assertFalse(failure.getMessage().contains(temporaryDirectory.toString()));
  }

  @Test
  void rejectsUnknownFieldsBadTypesObsoleteWorkspaceRoutingAndOversizeRequests() {
    AgentToolRequestValidator validator = validator();
    assertStatus(
        AgentToolValues.Status.INVALID_ARGUMENT,
        () -> validator.validate("trace", Map.of("startObjectId", "a", "depth", 2), 90, AgentCallDeadline.start()));
    assertStatus(
        AgentToolValues.Status.INVALID_ARGUMENT,
        () -> validator.validate("build_context", Map.of("target", "x", "intent", "DEBUG", "tokenBudget", 512), 90, AgentCallDeadline.start()));
    assertStatus(
        AgentToolValues.Status.INVALID_ARGUMENT,
        () -> validator.validate("knowledge_get", Map.of("workspaceId", "repo", "objectId", "document:v1:one"), 90, AgentCallDeadline.start()));
    assertStatus(
        AgentToolValues.Status.INVALID_ARGUMENT,
        () -> validator.validate("knowledge_get", Map.of("objectId", "document:v1:one"), 65_537, AgentCallDeadline.start()));
  }

  @Test
  void rejectsUnsafeLiteralScopesBudgetsAndObjectIds() {
    AgentToolRequestValidator validator = validator();
    assertInvalid(() -> validator.validate("code_search", Map.of("literalQuery", "a\nb"), 40, AgentCallDeadline.start()));
    assertInvalid(() -> validator.validate("code_search", Map.of("literalQuery", "x", "relativeScopes", List.of("../outside")), 40, AgentCallDeadline.start()));
    assertInvalid(() -> validator.validate("build_context", Map.of("target", "x", "intent", "CHANGE", "tokenBudget", 32_769), 40, AgentCallDeadline.start()));
    assertInvalid(() -> validator.validate("trace", Map.of("startObjectId", "bad\u0000id"), 40, AgentCallDeadline.start()));
  }

  @Test
  void distinguishesExpiredDeadlineAndCancellation() throws Exception {
    AtomicLong time = new AtomicLong(100);
    AgentCallDeadline expired = AgentCallDeadline.start(Duration.ofNanos(1), time::get);
    time.incrementAndGet();
    assertStatus(AgentToolValues.Status.DEADLINE_EXCEEDED, expired::ensureActive);

    Thread.currentThread().interrupt();
    try {
      assertStatus(AgentToolValues.Status.CANCELLED, AgentCallDeadline.start()::ensureActive);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void enforcesBothProtocolPayloadLimits() throws Exception {
    AgentPayloadLimits.ensureRequestSize(65_536);
    AgentPayloadLimits.ensureCompleteResponseSize(1_048_576);
    assertStatus(
        AgentToolValues.Status.INVALID_ARGUMENT,
        () -> AgentPayloadLimits.ensureRequestSize(65_537));
    assertStatus(
        AgentToolValues.Status.INTERNAL,
        () -> AgentPayloadLimits.ensureCompleteResponseSize(1_048_577));
  }

  @Test
  void relativizesWorkspacePathsAndSuppressesSensitiveDiagnostics() throws Exception {
    AgentOutputSanitizer sanitizer = new AgentOutputSanitizer(catalog());
    assertEquals("src/Main.java", sanitizer.relativePath("repo", temporaryDirectory.resolve("src/Main.java").toString()));
    assertStatus(AgentToolValues.Status.INTERNAL, () -> sanitizer.relativePath("repo", temporaryDirectory.resolve("../outside.txt").toString()));
    assertEquals("source is temporarily unavailable", sanitizer.diagnostic("SOURCE_UNAVAILABLE", "source is temporarily unavailable").detail());
    String suppressed = sanitizer.diagnostic("FAILED", "SQLException at C:\\secret\\store.db").detail();
    assertEquals("operation did not complete", suppressed);
    assertFalse(suppressed.contains("secret"));
  }

  private AgentToolRequestValidator validator() {
    return new AgentToolRequestValidator(catalog());
  }

  private ImmutableEngineeringWorkspaceCatalog catalog() {
    return new ImmutableEngineeringWorkspaceCatalog(List.of(new EngineeringWorkspace("repo", temporaryDirectory)));
  }

  private static void assertInvalid(ThrowingCall call) {
    assertStatus(AgentToolValues.Status.INVALID_ARGUMENT, call);
  }

  private static void assertStatus(AgentToolValues.Status status, ThrowingCall call) {
    AgentRequestException failure = assertThrows(AgentRequestException.class, call::run);
    assertEquals(status, failure.status());
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }
}
