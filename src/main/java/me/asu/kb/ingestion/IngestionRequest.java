package me.asu.kb.ingestion;

import java.util.List;

/** Bounded request containing immutable source units for one registered workspace. */
public record IngestionRequest(
    String workspaceId, List<IngestionSourceUnit> sourceUnits, int maxUnits, int maxObjects) {

  public IngestionRequest {
    sourceUnits = sourceUnits == null ? List.of() : List.copyOf(sourceUnits);
  }

  public static IngestionRequest of(
      String workspaceId, List<IngestionSourceUnit> sourceUnits) {
    return new IngestionRequest(workspaceId, sourceUnits, 0, 0);
  }
}
