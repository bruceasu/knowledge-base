package me.asu.kb.ingestion;

import java.util.Objects;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitToolProfile;

/** One immutable Git commit candidate plus the observed Adapter profile that produced it. */
public record GitCommitIngestionUnit(
    String stableSourceId, GitCommitEvidence commit, GitToolProfile profile)
    implements IngestionSourceUnit {

  public GitCommitIngestionUnit {
    stableSourceId = requireText(stableSourceId, "stableSourceId");
    commit = Objects.requireNonNull(commit, "commit");
    profile = Objects.requireNonNull(profile, "profile");
  }

  @Override
  public IngestionSourceCategory category() {
    return IngestionSourceCategory.GIT_EVIDENCE;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
