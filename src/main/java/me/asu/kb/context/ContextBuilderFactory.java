package me.asu.kb.context;

import java.util.ArrayList;
import java.util.List;
import me.asu.kb.read.KnowledgeObjectReadRequest;
import me.asu.kb.read.KnowledgeObjectReader;
import me.asu.kb.trace.KnowledgeRelationTracer;
import me.asu.kb.trace.RelationTraceRequest;
import me.asu.kb.trace.RelationTraceStatus;
import me.asu.kb.retrieval.KnowledgeRetriever;

/** Public factory that keeps the Context Builder's relation adapter package-private. */
public final class ContextBuilderFactory {
  private ContextBuilderFactory() {}

  public static ContextBuilder create(
      KnowledgeRetriever retriever,
      KnowledgeRelationTracer tracer,
      KnowledgeObjectReader objectReader) {
    return new DefaultContextBuilder(
        retriever,
        (workspaceId, objectId) -> readRelations(workspaceId, objectId, tracer, objectReader));
  }

  private static ContextRelationReadResult readRelations(
      String workspaceId,
      String objectId,
      KnowledgeRelationTracer tracer,
      KnowledgeObjectReader objectReader) {
    var trace = tracer.trace(new RelationTraceRequest(workspaceId, objectId));
    if (trace.status() == RelationTraceStatus.UNAVAILABLE) {
      return ContextRelationReadResult.failed(objectId, "relation source is unavailable");
    }
    if (trace.status() == RelationTraceStatus.NOT_FOUND) {
      return new ContextRelationReadResult(List.of(), List.of(), trace.complete());
    }
    List<DirectContextRelation> relations = new ArrayList<>();
    for (var relation : trace.relations()) {
      var opposite =
          relation.source().objectId().equals(objectId) ? relation.target() : relation.source();
      var read =
          objectReader.read(new KnowledgeObjectReadRequest(workspaceId, opposite.objectId()));
      if (read.object().isEmpty()) {
        return ContextRelationReadResult.failed(objectId, "related object is unavailable");
      }
      var view = read.object().orElseThrow();
      relations.add(
          new DirectContextRelation(
              workspaceId,
              relation.relationId(),
              objectId,
              relation.source().objectId(),
              relation.relationType(),
              relation.target().objectId(),
              view.object(),
              view.freshness(),
              relation.evidence()));
    }
    return new ContextRelationReadResult(relations, List.of(), trace.complete());
  }
}
