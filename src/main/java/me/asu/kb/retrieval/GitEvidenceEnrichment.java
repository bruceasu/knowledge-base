package me.asu.kb.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;

/** Path-keyed Git evidence that can enrich only an already selected source location. */
record GitEvidenceEnrichment(
    Map<String, List<Evidence>> evidenceByPath,
    List<RetrievalDiagnostic> diagnostics,
    boolean truncated) {

  GitEvidenceEnrichment {
    Objects.requireNonNull(evidenceByPath, "evidenceByPath");
    Map<String, List<Evidence>> copy = new LinkedHashMap<>();
    evidenceByPath.forEach((path, evidence) -> copy.put(path, List.copyOf(evidence)));
    evidenceByPath = Map.copyOf(copy);
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }
}
