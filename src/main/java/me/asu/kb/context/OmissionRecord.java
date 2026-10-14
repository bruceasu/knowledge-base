package me.asu.kb.context;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.SourceRef;

/** Candidate-level record explaining why content was not fully selected. */
public record OmissionRecord(
    OmissionReason reason,
    Optional<String> objectId,
    Optional<SourceRef> source,
    String detail) {

  public OmissionRecord {
    reason = Objects.requireNonNull(reason, "reason");
    objectId = optionalText(objectId, "objectId");
    source = Objects.requireNonNull(source, "source");
    detail = detail == null ? "" : detail;
  }

  private static Optional<String> optionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(
        item -> {
          if (item.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
          }
        });
    return value;
  }
}
