package me.asu.kb.ingestion;

import java.nio.file.Path;
import java.util.List;

/** Fully bounded request with ordered per-unit validation outcomes. */
record ValidatedIngestionRequest(
    String workspaceId,
    Path workspaceRoot,
    int maxObjects,
    List<IngestionUnitValidation> units) {

  ValidatedIngestionRequest {
    units = List.copyOf(units);
  }
}
