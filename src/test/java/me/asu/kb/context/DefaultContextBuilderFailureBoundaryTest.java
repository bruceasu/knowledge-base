package me.asu.kb.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RankExplanation;
import me.asu.kb.retrieval.RetrievalDiagnostic;
import me.asu.kb.retrieval.RetrievalDiagnosticCode;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalMatchCategory;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.retrieval.RetrievalSource;
import org.junit.jupiter.api.Test;

class DefaultContextBuilderFailureBoundaryTest {
  private static final String WORKSPACE_ID = "fixture";

  @Test
  void returnsAvailableTargetWithPartialRetrievalDiagnostic() {
    RetrievalDiagnostic retrievalDiagnostic =
        new RetrievalDiagnostic(
            Optional.of(RetrievalSource.RIPGREP_TEXT),
            RetrievalDiagnosticCode.SOURCE_FAILED,
            "ripgrep fixture failed");
    ContextBuilder builder =
        new DefaultContextBuilder(
            query ->
                new RetrievalResult(
                    WORKSPACE_ID, List.of(target()), List.of(retrievalDiagnostic), false),
            (workspaceId, objectId) -> completeRelations());

    EngineeringContext context = builder.build(request());

    assertEquals(List.of("symbol:target"), objectIds(context));
    assertFalse(context.complete());
    assertEquals(ContextDiagnosticCode.RETRIEVAL_INCOMPLETE, context.diagnostics().get(0).code());
    assertEquals(
        Optional.of(retrievalDiagnostic), context.diagnostics().get(0).retrievalDiagnostic());
    assertTrue(
        context.omissions().stream()
            .anyMatch(omission -> omission.reason() == OmissionReason.SOURCE_FAILURE));
  }

  @Test
  void returnsAvailableItemsWithPartialRelationDiagnostic() {
    ContextDiagnostic relationDiagnostic =
        new ContextDiagnostic(
            ContextDiagnosticCode.RELATION_READ_FAILED,
            "relation fixture incomplete",
            Optional.of("symbol:target"),
            Optional.empty());
    ContextBuilder builder =
        new DefaultContextBuilder(
            query -> completeRetrieval(),
            (workspaceId, objectId) ->
                new ContextRelationReadResult(
                    List.of(directDesignRelation(objectId)), List.of(relationDiagnostic), false));

    EngineeringContext context = builder.build(request());

    assertEquals(List.of("symbol:target", "design:context"), objectIds(context));
    assertFalse(context.complete());
    assertTrue(context.diagnostics().contains(relationDiagnostic));
  }

  @Test
  void returnsTargetNotFoundWithoutReadingRelationsForEmptyRetrieval() {
    AtomicInteger relationReads = new AtomicInteger();
    ContextBuilder builder =
        new DefaultContextBuilder(
            query -> new RetrievalResult(WORKSPACE_ID, List.of(), List.of(), true),
            (workspaceId, objectId) -> {
              relationReads.incrementAndGet();
              return completeRelations();
            });

    EngineeringContext context = builder.build(request());

    assertTrue(context.items().isEmpty());
    assertFalse(context.complete());
    assertEquals(0, relationReads.get());
    assertEquals(ContextDiagnosticCode.TARGET_NOT_FOUND, context.diagnostics().get(0).code());
  }

  @Test
  void contextModuleHasNoInfrastructureOrTransportRegistration() throws IOException {
    Path sourceRoot = Path.of("src", "main", "java");
    Path contextRoot = sourceRoot.resolve(Path.of("me", "asu", "kb", "context"));
    List<String> forbiddenImports =
        List.of(
            "import me.asu.kb.store.",
            "import me.asu.kb.adapter.",
            "import me.asu.kb.llm.",
            "import me.asu.kb.http.",
            "import me.asu.kb.cli.");

    try (var files = Files.walk(contextRoot)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        for (String forbiddenImport : forbiddenImports) {
          assertFalse(source.contains(forbiddenImport), file + " imports " + forbiddenImport);
        }
        assertFalse(source.contains("new ProcessBuilder("), file + " starts an external process");
      }
    }

    for (String packageName : List.of("http", "cli", "mcp")) {
      Path transportRoot = sourceRoot.resolve(Path.of("me", "asu", "kb", packageName));
      if (!Files.exists(transportRoot)) {
        continue;
      }
      try (var files = Files.walk(transportRoot)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          assertFalse(source.contains("me.asu.kb.context"), file + " imports context package");
          assertFalse(source.contains("ContextBuilder"), file + " registers ContextBuilder");
        }
      }
    }
  }

  private static ContextRequest request() {
    return new ContextRequest(
        WORKSPACE_ID, "target", ContextIntent.DIAGNOSE, 4096, false, true);
  }

  private static RetrievalResult completeRetrieval() {
    return new RetrievalResult(WORKSPACE_ID, List.of(target()), List.of(), true);
  }

  private static RetrievalItem target() {
    return new RetrievalItem(
        Optional.of("symbol:target"),
        ObjectType.CODE_SYMBOL,
        "Target",
        Optional.of("target implementation"),
        SourceRef.of("repository", "src/Target.java"),
        Optional.of(KnowledgeClass.CANONICAL),
        RetrievalFreshness.CURRENT_CANONICAL,
        Set.of(RetrievalSource.STRUCTURED_STORE),
        List.of(),
        new RankExplanation(
            RetrievalFreshness.CURRENT_CANONICAL,
            RetrievalMatchCategory.EXACT_SYMBOL,
            true,
            0,
            "object:symbol:target",
            "fixed target"));
  }

  private static DirectContextRelation directDesignRelation(String targetId) {
    SourceRef designSource = SourceRef.of("repository", "docs/context.md");
    KnowledgeObject design =
        KnowledgeObject.canonical(
            "design:context",
            ObjectType.DESIGN,
            "Context design",
            Optional.of("design content"),
            designSource,
            Map.of(),
            "hash:design");
    Evidence evidence =
        new Evidence(EvidenceType.SOURCE_TEXT, designSource, "design relation", 0.9d);
    return new DirectContextRelation(
        WORKSPACE_ID,
        "relation:design",
        targetId,
        targetId,
        RelationType.DESCRIBES,
        design.id(),
        design,
        RetrievalFreshness.CURRENT_CANONICAL,
        List.of(evidence));
  }

  private static ContextRelationReadResult completeRelations(DirectContextRelation... relations) {
    return new ContextRelationReadResult(List.of(relations), List.of(), true);
  }

  private static List<String> objectIds(EngineeringContext context) {
    return context.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
