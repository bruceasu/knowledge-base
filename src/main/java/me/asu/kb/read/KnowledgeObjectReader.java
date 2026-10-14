package me.asu.kb.read;

/** Application-layer seam for reading one current engineering-knowledge object. */
@FunctionalInterface
public interface KnowledgeObjectReader {
  KnowledgeObjectReadResult read(KnowledgeObjectReadRequest request);
}
