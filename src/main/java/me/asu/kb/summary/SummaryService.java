package me.asu.kb.summary;

import me.asu.kb.ingest.Chunk;

public interface SummaryService {
  String summarizeDocument(String title, String content);

  String summarizeChunk(Chunk chunk);
}
