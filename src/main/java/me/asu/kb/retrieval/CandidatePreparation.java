package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;

/** Fusion-ready groups plus diagnostics for rejected or incomplete candidates. */
record CandidatePreparation(
    List<FusedCandidate> candidates, List<RetrievalDiagnostic> diagnostics, boolean truncated) {

  CandidatePreparation {
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }
}
