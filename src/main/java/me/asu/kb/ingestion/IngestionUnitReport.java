package me.asu.kb.ingestion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Auditable outcome for exactly one immutable source unit. */
public record IngestionUnitReport(
    String stableSourceId,
    IngestionSourceCategory category,
    IngestionStatus status,
    int objectCount,
    int evidenceCount,
    Optional<String> inputHash,
    Optional<String> profileId,
    Optional<String> profileVersion,
    List<IngestionDiagnostic> diagnostics) {

  public IngestionUnitReport {
    stableSourceId = requireText(stableSourceId, "stableSourceId");
    category = Objects.requireNonNull(category, "category");
    status = Objects.requireNonNull(status, "status");
    if (objectCount < 0 || evidenceCount < 0) {
      throw new IllegalArgumentException("report counts must not be negative");
    }
    inputHash = optionalText(inputHash, "inputHash");
    profileId = optionalText(profileId, "profileId");
    profileVersion = optionalText(profileVersion, "profileVersion");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  private static Optional<String> optionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(item -> requireText(item, name));
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
