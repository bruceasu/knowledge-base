package me.asu.kb.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteKnowledgeObjectReaderTest {
  @TempDir Path tempDir;

  @Test
  void readsCurrentObjectWithSourceFreshnessAndStoredEvidence() throws Exception {
    try (SqliteKnowledgeStore store = storeWithObject("object", evidenceMetadata())) {
      KnowledgeObjectReadResult result =
          new SqliteKnowledgeObjectReader("workspace", store)
              .read(new KnowledgeObjectReadRequest("workspace", "object"));

      assertEquals(KnowledgeObjectReadStatus.FOUND, result.status());
      KnowledgeObjectView view = result.object().orElseThrow();
      assertEquals("docs/object.md", view.object().source().locator());
      assertEquals(RetrievalFreshness.CURRENT_CANONICAL, view.freshness());
      assertEquals(1, view.evidence().size());
      assertEquals("line:7", view.evidence().get(0).location());
      assertTrue(result.diagnostics().isEmpty());
    }
  }

  @Test
  void returnsControlledNotFoundForMissingObjectAndUnknownWorkspace() throws Exception {
    try (SqliteKnowledgeStore store = storeWithObject("object", Map.of())) {
      SqliteKnowledgeObjectReader reader = new SqliteKnowledgeObjectReader("workspace", store);

      assertEquals(
          KnowledgeObjectReadStatus.NOT_FOUND,
          reader.read(new KnowledgeObjectReadRequest("workspace", "missing")).status());
      KnowledgeObjectReadResult unknown =
          reader.read(new KnowledgeObjectReadRequest("other", "object"));
      assertEquals(KnowledgeObjectReadStatus.NOT_FOUND, unknown.status());
      assertEquals(
          KnowledgeObjectReadDiagnostic.Code.UNKNOWN_WORKSPACE,
          unknown.diagnostics().get(0).code());
    }
  }

  @Test
  void hidesStoreFailureBehindUnavailableResult() throws Exception {
    SqliteKnowledgeStore store = storeWithObject("object", Map.of());
    SqliteKnowledgeObjectReader reader = new SqliteKnowledgeObjectReader("workspace", store);
    store.close();

    KnowledgeObjectReadResult result =
        reader.read(new KnowledgeObjectReadRequest("workspace", "object"));

    assertEquals(KnowledgeObjectReadStatus.UNAVAILABLE, result.status());
    assertTrue(result.object().isEmpty());
    assertEquals(
        KnowledgeObjectReadDiagnostic.Code.STORE_UNAVAILABLE,
        result.diagnostics().get(0).code());
  }

  private SqliteKnowledgeStore storeWithObject(String objectId, Map<String, String> metadata)
      throws Exception {
    SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve(objectId + ".sqlite"));
    KnowledgeObject object =
        KnowledgeObject.canonical(
            objectId,
            ObjectType.DOCUMENT,
            "Object title",
            Optional.of("Object content"),
            new SourceRef("repository", "docs/object.md", Optional.of("r1")),
            metadata,
            "object-hash");
    store.save(new ProjectionWrite(List.of(ObjectWrite.canonical(object)), List.of(), List.of()));
    return store;
  }

  private static Map<String, String> evidenceMetadata() {
    return Map.of(
        "provenance.evidence.type", "SOURCE_TEXT",
        "provenance.evidence.source-system", "repository",
        "provenance.evidence.source-locator", "docs/object.md",
        "provenance.evidence.source-revision", "r1",
        "provenance.evidence.location", "line:7",
        "provenance.evidence.confidence", "0.9");
  }
}
