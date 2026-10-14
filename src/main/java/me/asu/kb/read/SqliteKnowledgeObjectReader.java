package me.asu.kb.read;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.store.SqliteKnowledgeStore;

/** SQLite Adapter behind the controlled object-read application seam. */
public final class SqliteKnowledgeObjectReader implements KnowledgeObjectReader {
  private static final String EVIDENCE_PREFIX = "provenance.evidence.";

  private final String workspaceId;
  private final SqliteKnowledgeStore store;

  public SqliteKnowledgeObjectReader(String workspaceId, SqliteKnowledgeStore store) {
    this.workspaceId = requireText(workspaceId, "workspaceId");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public KnowledgeObjectReadResult read(KnowledgeObjectReadRequest request) {
    Objects.requireNonNull(request, "request");
    if (!workspaceId.equals(request.workspaceId())) {
      return KnowledgeObjectReadResult.notFound(
          List.of(
              new KnowledgeObjectReadDiagnostic(
                  KnowledgeObjectReadDiagnostic.Code.UNKNOWN_WORKSPACE,
                  "workspace is not registered")));
    }
    try {
      Optional<SqliteKnowledgeStore.StoredObject> stored = store.findObject(request.objectId());
      if (stored.isEmpty()
          || stored.get().lifecycleState() == SqliteKnowledgeStore.ObjectLifecycleState.STALE) {
        return KnowledgeObjectReadResult.notFound(List.of());
      }
      return found(stored.orElseThrow());
    } catch (SQLException | IllegalStateException failure) {
      return KnowledgeObjectReadResult.unavailable();
    }
  }

  private static KnowledgeObjectReadResult found(SqliteKnowledgeStore.StoredObject stored) {
    List<KnowledgeObjectReadDiagnostic> diagnostics = new ArrayList<>();
    List<Evidence> evidence = readEvidence(stored.object().metadata(), diagnostics);
    RetrievalFreshness freshness =
        stored.object().knowledgeClass() == KnowledgeClass.CANONICAL
            ? RetrievalFreshness.CURRENT_CANONICAL
            : RetrievalFreshness.CURRENT_DERIVED;
    return KnowledgeObjectReadResult.found(
        new KnowledgeObjectView(stored.object(), freshness, evidence), diagnostics);
  }

  private static List<Evidence> readEvidence(
      Map<String, String> metadata, List<KnowledgeObjectReadDiagnostic> diagnostics) {
    if (!metadata.keySet().stream().anyMatch(key -> key.startsWith(EVIDENCE_PREFIX))) {
      return List.of();
    }
    try {
      EvidenceType type = EvidenceType.valueOf(required(metadata, "type"));
      SourceRef source =
          new SourceRef(
              required(metadata, "source-system"),
              required(metadata, "source-locator"),
              Optional.ofNullable(metadata.get(EVIDENCE_PREFIX + "source-revision")));
      return List.of(
          new Evidence(
              type,
              source,
              required(metadata, "location"),
              Double.parseDouble(required(metadata, "confidence"))));
    } catch (IllegalArgumentException failure) {
      diagnostics.add(
          new KnowledgeObjectReadDiagnostic(
              KnowledgeObjectReadDiagnostic.Code.EVIDENCE_INCOMPLETE,
              "stored object evidence is incomplete"));
      return List.of();
    }
  }

  private static String required(Map<String, String> metadata, String suffix) {
    String value = metadata.get(EVIDENCE_PREFIX + suffix);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing evidence field");
    }
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
