package me.asu.kb.service;

import java.util.List;
import java.util.Objects;

/** Result of a three-way merge of plain-text content. */
public record TextPatchMergeResult(Status status, String mergedText, List<Conflict> conflicts) {

  public TextPatchMergeResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(conflicts, "conflicts");
    conflicts = List.copyOf(conflicts);
    if (status == Status.MERGED) {
      Objects.requireNonNull(mergedText, "mergedText");
      if (!conflicts.isEmpty()) {
        throw new IllegalArgumentException("A merged result cannot contain conflicts");
      }
    } else if (mergedText != null) {
      throw new IllegalArgumentException("A conflict result cannot contain merged text");
    }
  }

  public static TextPatchMergeResult merged(String text) {
    return new TextPatchMergeResult(Status.MERGED, text, List.of());
  }

  public static TextPatchMergeResult conflict(String base, String server, String submitted) {
    return new TextPatchMergeResult(
        Status.CONFLICT, null, List.of(new Conflict(base, server, submitted)));
  }

  public enum Status {
    MERGED,
    CONFLICT
  }

  /** Complete versions needed by a caller to render or resolve a conflict. */
  public record Conflict(String baseText, String serverText, String submittedText) {
    public Conflict {
      Objects.requireNonNull(baseText, "baseText");
      Objects.requireNonNull(serverText, "serverText");
      Objects.requireNonNull(submittedText, "submittedText");
    }
  }
}
