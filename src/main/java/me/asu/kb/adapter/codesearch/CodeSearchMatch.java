package me.asu.kb.adapter.codesearch;

import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;

/**
 * An in-memory text occurrence and its source evidence. It is not an engineering relation or a
 * persistence command.
 */
public record CodeSearchMatch(
    String relativePath,
    CodeSearchRange range,
    String excerpt,
    boolean excerptTruncated,
    String matchedText,
    Evidence evidence) {

  public CodeSearchMatch {
    relativePath = requireText(relativePath, "relativePath");
    range = Objects.requireNonNull(range, "range");
    excerpt = requireText(excerpt, "excerpt");
    matchedText = requireText(matchedText, "matchedText");
    evidence = Objects.requireNonNull(evidence, "evidence");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
