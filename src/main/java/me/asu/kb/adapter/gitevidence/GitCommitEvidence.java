package me.asu.kb.adapter.gitevidence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;

/** Immutable commit metadata and path-level version-control evidence. */
public record GitCommitEvidence(
    String objectId,
    List<String> parentObjectIds,
    String authorName,
    String authorEmail,
    Instant authoredAt,
    String committerName,
    String committerEmail,
    Instant committedAt,
    String subject,
    List<GitFileChange> changes,
    Evidence evidence) {

  public GitCommitEvidence {
    objectId = requireText(objectId, "objectId");
    parentObjectIds = parentObjectIds == null ? List.of() : List.copyOf(parentObjectIds);
    authorName = requirePresent(authorName, "authorName");
    authorEmail = requirePresent(authorEmail, "authorEmail");
    authoredAt = Objects.requireNonNull(authoredAt, "authoredAt");
    committerName = requirePresent(committerName, "committerName");
    committerEmail = requirePresent(committerEmail, "committerEmail");
    committedAt = Objects.requireNonNull(committedAt, "committedAt");
    subject = requirePresent(subject, "subject");
    changes = changes == null ? List.of() : List.copyOf(changes);
    evidence = Objects.requireNonNull(evidence, "evidence");
    if (evidence.type() != EvidenceType.VERSION_CONTROL) {
      throw new IllegalArgumentException("evidence must be version control");
    }
  }

  private static String requireText(String value, String name) {
    requirePresent(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requirePresent(String value, String name) {
    return Objects.requireNonNull(value, name);
  }
}
