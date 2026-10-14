package me.asu.kb.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Observable projection status kept separate from file-save success. */
public record ProjectionStatus(
    ProjectionState state,
    long revisionNumber,
    Optional<String> failureReason,
    Instant updatedAt) {
  public ProjectionStatus {
    Objects.requireNonNull(state, "state");
    if (revisionNumber <= 0) {
      throw new IllegalArgumentException("Revision number must be positive");
    }
    Objects.requireNonNull(failureReason, "failureReason");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (state == ProjectionState.FAILED && failureReason.isEmpty()) {
      throw new IllegalArgumentException("Failed projection must have a failure reason");
    }
    if (state != ProjectionState.FAILED && failureReason.isPresent()) {
      throw new IllegalArgumentException("Only failed projection may have a failure reason");
    }
  }
}
