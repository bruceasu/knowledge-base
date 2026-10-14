package me.asu.kb.adapter.gitevidence;

import java.nio.file.Path;
import java.util.List;

/**
 * Narrow process-facing validation contract. Inputs are already grammar-checked complete OIDs;
 * implementations own every Git command and option.
 */
interface GitRepositoryProbe {

  GitRepositoryFacts repositoryFacts(Path canonicalWorkspace, GitExecutionBudget budget)
      throws GitProbeException;

  GitObjectStatus objectStatus(
      Path canonicalGitRoot, String completeObjectId, GitExecutionBudget budget)
      throws GitProbeException;

  boolean isAncestor(
      Path canonicalGitRoot,
      String oldestObjectId,
      String newestObjectId,
      GitExecutionBudget budget)
      throws GitProbeException;

  List<String> commitsInInclusiveRange(
      Path canonicalGitRoot,
      String oldestObjectId,
      String newestObjectId,
      int maximumToReturn,
      GitExecutionBudget budget)
      throws GitProbeException;
}
