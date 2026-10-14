package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;

/** One source's bounded normalized output and diagnostics. */
record CandidateBatch(
    RetrievalSource source,
    List<RetrievalCandidate> candidates,
    List<RetrievalDiagnostic> diagnostics,
    boolean truncated) {

  CandidateBatch {
    source = Objects.requireNonNull(source, "source");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    for (RetrievalCandidate candidate : candidates) {
      if (candidate.retrievalSource() != source) {
        throw new IllegalArgumentException("candidate source does not match batch source");
      }
    }
  }
}
