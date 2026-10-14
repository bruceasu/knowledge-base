package me.asu.kb.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeObjectTest {

  private static final SourceRef SOURCE =
      new SourceRef("workspace-file", "docs/architecture.md", Optional.of("revision-7"));

  @Test
  void canonicalObjectRetainsSourceAndDefensivelyCopiesMetadata() {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("tag", "architecture");

    KnowledgeObject object =
        KnowledgeObject.canonical(
            "document:architecture",
            ObjectType.DOCUMENT,
            "Architecture",
            Optional.of("The architecture text."),
            SOURCE,
            metadata,
            "sha256:canonical");

    metadata.put("later", "mutation");

    assertEquals(KnowledgeClass.CANONICAL, object.knowledgeClass());
    assertEquals(SOURCE, object.source());
    assertEquals(Map.of("tag", "architecture"), object.metadata());
    assertThrows(
        UnsupportedOperationException.class, () -> object.metadata().put("new", "value"));
  }

  @Test
  void derivedObjectRequiresCompleteProvenance() {
    KnowledgeObject derived =
        KnowledgeObject.derived(
            "chunk:architecture:0",
            ObjectType.CHUNK,
            "Architecture chunk",
            Optional.of("A derived segment."),
            SOURCE,
            Map.of(),
            "sha256:derived",
            "document:architecture",
            "text-chunker",
            "1.0",
            "sha256:canonical");

    assertEquals(Optional.of("document:architecture"), derived.canonicalSourceId());
    assertEquals(Optional.of("text-chunker"), derived.generatorId());
    assertEquals(Optional.of("1.0"), derived.generatorVersion());
    assertEquals(Optional.of("sha256:canonical"), derived.inputContentHash());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KnowledgeObject(
                "chunk:incomplete",
                ObjectType.CHUNK,
                "Incomplete",
                Optional.empty(),
                SOURCE,
                Map.of(),
                KnowledgeClass.DERIVED,
                "sha256:derived",
                Optional.of("document:architecture"),
                Optional.empty(),
                Optional.of("1.0"),
                Optional.of("sha256:canonical")));
  }

  @Test
  void canonicalObjectRejectsDerivedProvenance() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KnowledgeObject(
                "document:invalid",
                ObjectType.DOCUMENT,
                "Invalid canonical",
                Optional.empty(),
                SOURCE,
                Map.of(),
                KnowledgeClass.CANONICAL,
                "sha256:canonical",
                Optional.of("document:other"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void relationRequiresAuditableEvidence() {
    Evidence evidence =
        new Evidence(
            EvidenceType.STATIC_ANALYSIS,
            SOURCE,
            "src/main/java/me/asu/kb/service/KnowledgeBase.java:39-77",
            0.9d);
    Relation relation =
        new Relation(
            "requirement:knowledge-core", "document:architecture", RelationType.DESCRIBES, evidence);

    assertEquals(evidence, relation.evidence());
    assertEquals(RelationType.DESCRIBES, relation.type());
    assertThrows(
        NullPointerException.class,
        () ->
            new Relation(
                "requirement:knowledge-core", "document:architecture", RelationType.DESCRIBES, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Evidence(EvidenceType.LLM_DERIVATION, SOURCE, "analysis", 1.1d));
  }
}
