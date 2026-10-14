package me.asu.kb.context;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.retrieval.RetrievalDiagnostic;

/** Safe structured diagnostic, optionally retaining its retrieval diagnostic. */
public record ContextDiagnostic(
    ContextDiagnosticCode code,
    String detail,
    Optional<String> objectId,
    Optional<RetrievalDiagnostic> retrievalDiagnostic) {

  public ContextDiagnostic {
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
    objectId = optionalText(objectId, "objectId");
    retrievalDiagnostic = Objects.requireNonNull(retrievalDiagnostic, "retrievalDiagnostic");
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
