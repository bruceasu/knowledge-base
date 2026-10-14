package me.asu.kb.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import me.asu.kb.store.SqliteKnowledgeStore.RelationProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteKnowledgeRelationTracerTest {
  @TempDir Path tempDir;

  @Test
  void returnsOneHopEvidenceWithEndpointSourcesAndFreshness() throws Exception {
    try (SqliteKnowledgeStore store = storeWithRelation()) {
      RelationTraceResult result =
          new SqliteKnowledgeRelationTracer("workspace", store)
              .trace(new RelationTraceRequest("workspace", "document"));

      assertEquals(RelationTraceStatus.FOUND, result.status());
      assertEquals(RetrievalFreshness.CURRENT_CANONICAL, result.startObject().orElseThrow().freshness());
      assertEquals(1, result.relations().size());
      TracedRelation relation = result.relations().get(0);
      assertEquals(RelationType.DESCRIBES, relation.relationType());
      assertEquals("docs/document.md", relation.source().source().locator());
      assertEquals("docs/section.md", relation.target().source().locator());
      assertEquals("line:4", relation.evidence().get(0).location());
      assertTrue(result.complete());
      assertTrue(result.diagnostics().isEmpty());
    }
  }

  @Test
  void returnsControlledNotFoundForMissingObjectAndUnknownWorkspace() throws Exception {
    try (SqliteKnowledgeStore store = storeWithRelation()) {
      SqliteKnowledgeRelationTracer tracer =
          new SqliteKnowledgeRelationTracer("workspace", store);

      assertEquals(
          RelationTraceStatus.NOT_FOUND,
          tracer.trace(new RelationTraceRequest("workspace", "missing")).status());
      RelationTraceResult unknown =
          tracer.trace(new RelationTraceRequest("other", "document"));
      assertEquals(RelationTraceStatus.NOT_FOUND, unknown.status());
      assertEquals(
          RelationTraceDiagnostic.Code.UNKNOWN_WORKSPACE,
          unknown.diagnostics().get(0).code());
    }
  }

  @Test
  void appliesFixedRelationLimitAndReportsIncompleteResult() throws Exception {
    try (SqliteKnowledgeStore store = storeWithManyRelations()) {
      RelationTraceResult result =
          new SqliteKnowledgeRelationTracer("workspace", store)
              .trace(new RelationTraceRequest("workspace", "document"));

      assertEquals(SqliteKnowledgeRelationTracer.MAX_RELATIONS, result.relations().size());
      assertFalse(result.complete());
      assertEquals(
          RelationTraceDiagnostic.Code.RESULT_LIMIT_REACHED,
          result.diagnostics().get(0).code());
    }
  }

  @Test
  void hidesStoreFailureBehindUnavailableResult() throws Exception {
    SqliteKnowledgeStore store = storeWithRelation();
    SqliteKnowledgeRelationTracer tracer =
        new SqliteKnowledgeRelationTracer("workspace", store);
    store.close();

    RelationTraceResult result =
        tracer.trace(new RelationTraceRequest("workspace", "document"));

    assertEquals(RelationTraceStatus.UNAVAILABLE, result.status());
    assertTrue(result.relations().isEmpty());
    assertEquals(
        RelationTraceDiagnostic.Code.STORE_UNAVAILABLE,
        result.diagnostics().get(0).code());
  }

  private SqliteKnowledgeStore storeWithRelation() throws Exception {
    SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve("relation.sqlite"));
    KnowledgeObject document = object("document");
    KnowledgeObject section = object("section");
    store.save(
        new ProjectionWrite(
            List.of(ObjectWrite.canonical(document), ObjectWrite.canonical(section)),
            List.of(
                new RelationProjection(
                    "document", RelationType.DESCRIBES, "section", List.of(evidence("line:4")))),
            List.of()));
    return store;
  }

  private SqliteKnowledgeStore storeWithManyRelations() throws Exception {
    SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve("many-relations.sqlite"));
    List<ObjectWrite> objects = new java.util.ArrayList<>();
    List<RelationProjection> relations = new java.util.ArrayList<>();
    objects.add(ObjectWrite.canonical(object("document")));
    for (int index = 0; index <= SqliteKnowledgeRelationTracer.MAX_RELATIONS; index++) {
      String targetId = "target-" + index;
      objects.add(ObjectWrite.canonical(object(targetId)));
      relations.add(
          new RelationProjection(
              "document", RelationType.RELATED_TO, targetId, List.of(evidence("line:" + index))));
    }
    store.save(new ProjectionWrite(objects, relations, List.of()));
    return store;
  }

  private static KnowledgeObject object(String objectId) {
    return KnowledgeObject.canonical(
        objectId,
        ObjectType.DOCUMENT,
        objectId + " title",
        Optional.of(objectId + " content"),
        new SourceRef("repository", "docs/" + objectId + ".md", Optional.of("r1")),
        Map.of(),
        objectId + "-hash");
  }

  private static Evidence evidence(String location) {
    return new Evidence(
        EvidenceType.SOURCE_TEXT,
        new SourceRef("repository", "docs/evidence.md", Optional.of("r1")),
        location,
        0.9d);
  }
}
