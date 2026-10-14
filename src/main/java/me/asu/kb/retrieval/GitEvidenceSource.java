package me.asu.kb.retrieval;

import java.util.List;

/** Internal bounded Git enrichment seam; paths always come from already matched candidates. */
@FunctionalInterface
interface GitEvidenceSource {
  GitEvidenceEnrichment enrich(ValidatedKnowledgeQuery query, List<String> matchedPaths);

  static GitEvidenceSource disabled() {
    return (query, paths) -> new GitEvidenceEnrichment(java.util.Map.of(), List.of(), false);
  }
}
