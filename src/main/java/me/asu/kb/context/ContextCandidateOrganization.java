package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministically deduplicated and prioritized candidates before budget packing. */
record ContextCandidateOrganization(
    Optional<ContextRequest> request,
    List<SelectedContextCandidate> candidates,
    List<ContextDiagnostic> diagnostics,
    List<OmissionRecord> omissions,
    boolean complete) {

  ContextCandidateOrganization {
    request = Objects.requireNonNull(request, "request");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    omissions = List.copyOf(Objects.requireNonNull(omissions, "omissions"));
    if (!candidates.isEmpty() && request.isEmpty()) {
      throw new IllegalArgumentException("organized candidates require a validated request");
    }
    if (complete && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("a complete organization cannot contain diagnostics");
    }
  }
}
