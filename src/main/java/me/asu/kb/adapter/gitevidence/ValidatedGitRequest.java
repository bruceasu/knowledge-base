package me.asu.kb.adapter.gitevidence;

import java.nio.file.Path;
import java.util.List;

/** Internal validated plan; it contains no raw revision expression, command, option, or shell text. */
record ValidatedGitRequest(
    String workspaceId,
    Path workspaceRoot,
    GitRepositoryFacts repository,
    List<String> commitObjectIds,
    List<String> workspaceRelativeFilters,
    List<String> repositoryRelativeFilters,
    int commitLimit,
    int changeLimit,
    boolean commitTruncated) {

  ValidatedGitRequest {
    commitObjectIds = List.copyOf(commitObjectIds);
    workspaceRelativeFilters = List.copyOf(workspaceRelativeFilters);
    repositoryRelativeFilters = List.copyOf(repositoryRelativeFilters);
  }
}
