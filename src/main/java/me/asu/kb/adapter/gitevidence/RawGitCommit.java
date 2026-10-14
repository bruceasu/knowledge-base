package me.asu.kb.adapter.gitevidence;

import java.time.Instant;
import java.util.List;

/** Parsed Git commit metadata and repository-relative changes before evidence projection. */
record RawGitCommit(
    String objectId,
    List<String> parentObjectIds,
    String authorName,
    String authorEmail,
    Instant authoredAt,
    String committerName,
    String committerEmail,
    Instant committedAt,
    String subject,
    List<RawGitFileChange> changes) {
  RawGitCommit {
    parentObjectIds = List.copyOf(parentObjectIds);
    changes = List.copyOf(changes);
  }
}
