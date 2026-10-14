package me.asu.kb.adapter.document;

/** One-based inclusive line range in the supplied revision content. */
public record SourceRange(int startLine, int endLine) {

  public SourceRange {
    if (startLine <= 0) {
      throw new IllegalArgumentException("startLine must be positive");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine must not precede startLine");
    }
  }
}
