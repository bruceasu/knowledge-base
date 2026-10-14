package me.asu.kb.adapter.gitevidence;

import java.util.List;

/**
 * A bounded local Git evidence request. Semantic validation belongs to
 * {@link GitKnowledgeAdapter#inspect(GitEvidenceRequest)} so invalid input can be returned as a
 * structured diagnostic before any process starts.
 */
public record GitEvidenceRequest(
    String workspaceId,
    GitCommitSelection commitSelection,
    List<String> relativePathFilters,
    int maxCommits,
    int maxChanges) {

  public GitEvidenceRequest {
    relativePathFilters =
        relativePathFilters == null ? List.of() : List.copyOf(relativePathFilters);
  }

  /** Creates an unfiltered explicit selection using the Adapter's configured budgets. */
  public static GitEvidenceRequest explicit(String workspaceId, List<String> objectIds) {
    return new GitEvidenceRequest(
        workspaceId, new ExplicitCommits(objectIds), List.of(), 0, 0);
  }

  /** Creates an unfiltered inclusive range using the Adapter's configured budgets. */
  public static GitEvidenceRequest inclusiveRange(
      String workspaceId, String oldestInclusive, String newestInclusive) {
    return new GitEvidenceRequest(
        workspaceId,
        new InclusiveCommitRange(oldestInclusive, newestInclusive),
        List.of(),
        0,
        0);
  }
}
