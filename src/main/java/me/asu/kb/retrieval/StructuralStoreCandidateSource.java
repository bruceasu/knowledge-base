package me.asu.kb.retrieval;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.store.SqliteKnowledgeStore;

/** Bounded structural retrieval over one workspace-bound authoritative Store. */
final class StructuralStoreCandidateSource implements CandidateSource {
  private final String workspaceId;
  private final SqliteKnowledgeStore store;

  StructuralStoreCandidateSource(String workspaceId, SqliteKnowledgeStore store) {
    this.workspaceId = requireText(workspaceId, "workspaceId");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public RetrievalSource source() {
    return RetrievalSource.STRUCTURED_STORE;
  }

  @Override
  public CandidateBatch collect(ValidatedKnowledgeQuery query) {
    if (!workspaceId.equals(query.workspaceId())) {
      return failed("structural Store is not bound to the requested workspace");
    }
    int limit = query.budgets().storeCandidates();
    try {
      List<SqliteKnowledgeStore.StoredObject> snapshot =
          store.findObjects(limit, query.includeStale());
      boolean truncated = snapshot.size() == limit;
      String needle = query.text().toLowerCase(Locale.ROOT);
      List<RetrievalCandidate> candidates = new ArrayList<>();
      for (SqliteKnowledgeStore.StoredObject stored : snapshot) {
        KnowledgeObject object = stored.object();
        if (!query.objectTypes().isEmpty() && !query.objectTypes().contains(object.type())) {
          continue;
        }
        if (!matches(object, needle)) {
          continue;
        }
        RetrievalFreshness freshness =
            stored.lifecycleState() == SqliteKnowledgeStore.ObjectLifecycleState.STALE
                ? RetrievalFreshness.STALE
                : object.knowledgeClass() == KnowledgeClass.CANONICAL
                    ? RetrievalFreshness.CURRENT_CANONICAL
                    : RetrievalFreshness.CURRENT_DERIVED;
        candidates.add(
            new RetrievalCandidate(
                Optional.of(object.id()),
                object.type(),
                object.title(),
                object.content().map(StructuralStoreCandidateSource::excerpt),
                object.source(),
                Optional.of(object.knowledgeClass()),
                freshness,
                exactIdentifier(object, query.text())
                    ? RetrievalMatchCategory.EXACT_IDENTIFIER
                    : RetrievalMatchCategory.STRUCTURED_OBJECT,
                RetrievalSource.STRUCTURED_STORE,
                candidates.size(),
                true,
                List.of(),
                object.inputContentHash(),
                object.generatorVersion()));
      }
      List<RetrievalDiagnostic> diagnostics =
          truncated
              ? List.of(
                  new RetrievalDiagnostic(
                      Optional.of(RetrievalSource.STRUCTURED_STORE),
                      RetrievalDiagnosticCode.SOURCE_TRUNCATED,
                      "structural Store candidate budget reached"))
              : List.of();
      return new CandidateBatch(
          RetrievalSource.STRUCTURED_STORE, candidates, diagnostics, truncated);
    } catch (SQLException failure) {
      return failed("structural Store read failed");
    }
  }

  private CandidateBatch failed(String detail) {
    return new CandidateBatch(
        RetrievalSource.STRUCTURED_STORE,
        List.of(),
        List.of(
            new RetrievalDiagnostic(
                Optional.of(RetrievalSource.STRUCTURED_STORE),
                RetrievalDiagnosticCode.SOURCE_FAILED,
                detail)),
        false);
  }

  private static boolean matches(KnowledgeObject object, String needle) {
    return object.id().toLowerCase(Locale.ROOT).contains(needle)
        || object.title().toLowerCase(Locale.ROOT).contains(needle)
        || object.content().map(value -> value.toLowerCase(Locale.ROOT).contains(needle)).orElse(false)
        || object.metadata().entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.getValue().toLowerCase(Locale.ROOT).contains(needle));
  }

  private static boolean exactIdentifier(KnowledgeObject object, String text) {
    return object.id().equalsIgnoreCase(text)
        || object.metadata().getOrDefault("symbol.identity", "").equalsIgnoreCase(text)
        || object.metadata().getOrDefault("symbol.qualified-name", "").equalsIgnoreCase(text);
  }

  private static String excerpt(String content) {
    return content.length() <= 240 ? content : content.substring(0, 240);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
