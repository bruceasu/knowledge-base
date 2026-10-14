package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete or explicitly partial result of one deterministic context build. */
public record EngineeringContext(
    String workspaceId,
    String target,
    Optional<ContextIntent> intent,
    List<ContextItem> items,
    int estimatedTokens,
    String estimatorVersion,
    boolean complete,
    List<ContextDiagnostic> diagnostics,
    List<OmissionRecord> omissions) {

  public EngineeringContext {
    workspaceId = workspaceId == null ? "" : workspaceId;
    target = target == null ? "" : target;
    intent = Objects.requireNonNull(intent, "intent");
    items = items == null ? List.of() : List.copyOf(items);
    estimatorVersion = requireText(estimatorVersion, "estimatorVersion");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    omissions = omissions == null ? List.of() : List.copyOf(omissions);
    if (!ContextTokenEstimator.VERSION.equals(estimatorVersion)) {
      throw new IllegalArgumentException(
          "estimatorVersion must be " + ContextTokenEstimator.VERSION);
    }
    int expectedEstimate = ContextTokenEstimator.total(items);
    if (estimatedTokens != expectedEstimate) {
      throw new IllegalArgumentException("estimatedTokens must equal the sum of item estimates");
    }
    if (complete && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("a complete context cannot contain diagnostics");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
