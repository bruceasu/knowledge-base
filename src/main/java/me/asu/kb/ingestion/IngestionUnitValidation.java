package me.asu.kb.ingestion;

import java.util.Objects;
import java.util.Optional;

/** Ordered validation outcome for one requested source unit. */
record IngestionUnitValidation(
    IngestionSourceUnit sourceUnit,
    Optional<ValidatedIngestionUnit> accepted,
    Optional<IngestionUnitReport> rejected) {

  IngestionUnitValidation {
    sourceUnit = Objects.requireNonNull(sourceUnit, "sourceUnit");
    accepted = Objects.requireNonNull(accepted, "accepted");
    rejected = Objects.requireNonNull(rejected, "rejected");
    if (accepted.isPresent() == rejected.isPresent()) {
      throw new IllegalArgumentException("exactly one validation outcome is required");
    }
  }

  static IngestionUnitValidation accepted(
      IngestionSourceUnit sourceUnit, ValidatedIngestionUnit validated) {
    return new IngestionUnitValidation(sourceUnit, Optional.of(validated), Optional.empty());
  }

  static IngestionUnitValidation rejected(
      IngestionSourceUnit sourceUnit, IngestionUnitReport report) {
    return new IngestionUnitValidation(sourceUnit, Optional.empty(), Optional.of(report));
  }
}
