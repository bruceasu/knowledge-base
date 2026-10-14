package me.asu.kb.trace;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.store.SqliteKnowledgeStore;

/** SQLite Adapter behind the fixed one-hop relation-trace application seam. */
public final class SqliteKnowledgeRelationTracer implements KnowledgeRelationTracer {
  public static final int MAX_RELATIONS = 64;

  private final String workspaceId;
  private final SqliteKnowledgeStore store;

  public SqliteKnowledgeRelationTracer(String workspaceId, SqliteKnowledgeStore store) {
    this.workspaceId = requireText(workspaceId, "workspaceId");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public RelationTraceResult trace(RelationTraceRequest request) {
    Objects.requireNonNull(request, "request");
    if (!workspaceId.equals(request.workspaceId())) {
      return RelationTraceResult.notFound(
          List.of(
              new RelationTraceDiagnostic(
                  RelationTraceDiagnostic.Code.UNKNOWN_WORKSPACE,
                  "workspace is not registered")));
    }
    try {
      Optional<SqliteKnowledgeStore.StoredObject> storedStart =
          store.findObject(request.startObjectId());
      if (storedStart.isEmpty()) {
        return RelationTraceResult.notFound(List.of());
      }
      return trace(storedStart.orElseThrow());
    } catch (SQLException | IllegalStateException failure) {
      return RelationTraceResult.unavailable();
    }
  }

  private RelationTraceResult trace(SqliteKnowledgeStore.StoredObject storedStart)
      throws SQLException {
    List<SqliteKnowledgeStore.StoredRelation> storedRelations =
        store.findRelationsForObject(storedStart.object().id(), MAX_RELATIONS + 1);
    boolean complete = storedRelations.size() <= MAX_RELATIONS;
    List<RelationTraceDiagnostic> diagnostics = new ArrayList<>();
    if (!complete) {
      diagnostics.add(
          new RelationTraceDiagnostic(
              RelationTraceDiagnostic.Code.RESULT_LIMIT_REACHED,
              "relation trace reached its fixed result limit"));
    }

    Map<String, Optional<RelationTraceObject>> objects = new HashMap<>();
    RelationTraceObject startObject = toTraceObject(storedStart);
    objects.put(startObject.objectId(), Optional.of(startObject));
    List<TracedRelation> relations = new ArrayList<>();
    for (SqliteKnowledgeStore.StoredRelation storedRelation :
        storedRelations.subList(0, Math.min(storedRelations.size(), MAX_RELATIONS))) {
      Optional<RelationTraceObject> source = readObject(storedRelation.sourceObjectId(), objects);
      Optional<RelationTraceObject> target = readObject(storedRelation.targetObjectId(), objects);
      if (storedRelation.evidence().isEmpty() || source.isEmpty() || target.isEmpty()) {
        complete = false;
        diagnostics.add(
            new RelationTraceDiagnostic(
                RelationTraceDiagnostic.Code.RELATION_INCOMPLETE,
                "a relation was omitted because its evidence or endpoint is unavailable"));
        continue;
      }
      relations.add(
          new TracedRelation(
              storedRelation.relationId(),
              source.orElseThrow(),
              storedRelation.type(),
              target.orElseThrow(),
              storedRelation.evidence()));
    }
    return RelationTraceResult.found(startObject, relations, complete, diagnostics);
  }

  private Optional<RelationTraceObject> readObject(
      String objectId, Map<String, Optional<RelationTraceObject>> objects) throws SQLException {
    Optional<RelationTraceObject> cached = objects.get(objectId);
    if (cached != null) {
      return cached;
    }
    Optional<RelationTraceObject> loaded = store.findObject(objectId).map(this::toTraceObject);
    objects.put(objectId, loaded);
    return loaded;
  }

  private RelationTraceObject toTraceObject(SqliteKnowledgeStore.StoredObject stored) {
    RetrievalFreshness freshness =
        stored.lifecycleState() == SqliteKnowledgeStore.ObjectLifecycleState.STALE
            ? RetrievalFreshness.STALE
            : stored.object().knowledgeClass() == KnowledgeClass.CANONICAL
                ? RetrievalFreshness.CURRENT_CANONICAL
                : RetrievalFreshness.CURRENT_DERIVED;
    return new RelationTraceObject(
        stored.object().id(), stored.object().type(), stored.object().source(), freshness);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
