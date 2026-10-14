package me.asu.kb.ingestion;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Complete report for a request; independent unit failures remain visible and do not collapse. */
public record IngestionReport(String workspaceId, List<IngestionUnitReport> units) {

  public IngestionReport {
    workspaceId = workspaceId == null ? "" : workspaceId;
    units = units == null ? List.of() : List.copyOf(units);
  }

  public Map<IngestionStatus, Long> statusCounts() {
    Map<IngestionStatus, Long> counts = new EnumMap<>(IngestionStatus.class);
    for (IngestionUnitReport unit : units) {
      counts.merge(unit.status(), 1L, Long::sum);
    }
    return Map.copyOf(counts);
  }

  public boolean fullySuccessful() {
    return !units.isEmpty()
        && units.stream()
            .allMatch(
                unit ->
                    unit.status() == IngestionStatus.SAVED
                        || unit.status() == IngestionStatus.UNCHANGED
                        || unit.status() == IngestionStatus.STALE);
  }
}
