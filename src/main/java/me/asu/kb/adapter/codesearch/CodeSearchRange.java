package me.asu.kb.adapter.codesearch;

/** A one-based source range whose end column is exclusive. */
public record CodeSearchRange(int startLine, int startColumn, int endLine, int endColumn) {

  public CodeSearchRange {
    if (startLine < 1 || endLine < 1 || startColumn < 1 || endColumn < 1) {
      throw new IllegalArgumentException("source range positions must be positive");
    }
    if (endLine < startLine || (endLine == startLine && endColumn <= startColumn)) {
      throw new IllegalArgumentException("source range end must follow start");
    }
  }
}
