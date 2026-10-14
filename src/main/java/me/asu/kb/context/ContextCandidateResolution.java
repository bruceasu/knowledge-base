package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Target resolution and bounded one-hop expansion before ordering and packing. */
record ContextCandidateResolution(
    Optional<ContextRequest> request,
    Optional<ContextCandidate> target,
    List<ContextCandidate> related,
    List<ContextDiagnostic> diagnostics,
    List<OmissionRecord> omissions,
    boolean complete) {

  ContextCandidateResolution {
    request = Objects.requireNonNull(request, "request");
    target = Objects.requireNonNull(target, "target");
    related = List.copyOf(Objects.requireNonNull(related, "related"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    omissions = List.copyOf(Objects.requireNonNull(omissions, "omissions"));
    if (target.isPresent() && request.isEmpty()) {
      throw new IllegalArgumentException("a resolved target requires a validated request");
    }
    if (complete && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("a complete resolution cannot contain diagnostics");
    }
  }
}
