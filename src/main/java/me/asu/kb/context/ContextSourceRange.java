package me.asu.kb.context;

/** One-based inclusive source range retained for a rendered fragment. */
public record ContextSourceRange(int startLine, int endLine) {
  public ContextSourceRange {
    if (startLine <= 0) {
      throw new IllegalArgumentException("startLine must be positive");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine must not precede startLine");
    }
  }
}
