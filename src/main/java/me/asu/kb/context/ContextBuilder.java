package me.asu.kb.context;

/** Single application-layer seam for deterministic engineering-context assembly. */
@FunctionalInterface
public interface ContextBuilder {
  EngineeringContext build(ContextRequest request);
}
