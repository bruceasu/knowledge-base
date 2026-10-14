package me.asu.kb.adapter.gitevidence;

import java.util.Objects;
import java.util.Optional;

/** Parsed repository-relative tree change before workspace-boundary normalization. */
record RawGitFileChange(
    GitChangeKind kind, Optional<String> oldRepositoryPath, Optional<String> newRepositoryPath,
    boolean binary) {
  RawGitFileChange {
    kind = Objects.requireNonNull(kind, "kind");
    oldRepositoryPath = Objects.requireNonNull(oldRepositoryPath, "oldRepositoryPath");
    newRepositoryPath = Objects.requireNonNull(newRepositoryPath, "newRepositoryPath");
  }
}
