package me.asu.kb.trace;

/** Application-layer seam for one fixed-range, evidence-bearing relation trace. */
@FunctionalInterface
public interface KnowledgeRelationTracer {
  RelationTraceResult trace(RelationTraceRequest request);
}
