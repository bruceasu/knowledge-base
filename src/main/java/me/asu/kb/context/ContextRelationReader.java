package me.asu.kb.context;

/** Controlled application-layer seam for one bounded direct-relation read. */
@FunctionalInterface
interface ContextRelationReader {
  ContextRelationReadResult readDirectRelations(String workspaceId, String objectId);
}
