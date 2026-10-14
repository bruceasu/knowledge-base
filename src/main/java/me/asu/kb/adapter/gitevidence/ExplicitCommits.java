package me.asu.kb.adapter.gitevidence;

import java.util.List;

/** An explicit bounded set of complete commit object IDs. */
public record ExplicitCommits(List<String> objectIds) implements GitCommitSelection {

  public ExplicitCommits {
    objectIds = objectIds == null ? List.of() : List.copyOf(objectIds);
  }
}
