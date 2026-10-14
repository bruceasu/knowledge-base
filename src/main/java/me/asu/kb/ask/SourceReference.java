package me.asu.kb.ask;

import java.util.List;

public record SourceReference(
    long documentId,
    String path,
    String title,
    int chunkNo,
    String snippet,
    List<String> tags) {

  /** Preserves source compatibility for callers that do not yet have a document identity. */
  public SourceReference(String path, String title, int chunkNo, String snippet, List<String> tags) {
    this(0L, path, title, chunkNo, snippet, tags);
  }
}
