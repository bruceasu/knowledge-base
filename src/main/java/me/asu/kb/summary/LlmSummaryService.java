package me.asu.kb.summary;

import java.util.Locale;
import me.asu.kb.ingest.Chunk;
import me.asu.kb.llm.LlmClient;

public final class LlmSummaryService implements SummaryService {

  private final LlmClient llmClient;

  public LlmSummaryService(LlmClient llmClient) {
    this.llmClient = llmClient;
  }

  @Override
  public String summarizeDocument(String title, String content) {
    try {
      String prompt = buildDocumentPrompt(title, content);
      return normalizeSummary(llmClient.generate(prompt), title, content, "document");
    } catch (RuntimeException e) {
      return fallbackSummary(title, content, "document");
    }
  }

  @Override
  public String summarizeChunk(Chunk chunk) {
    if (chunk == null) {
      return "";
    }
    try {
      String prompt = buildChunkPrompt(chunk);
      return normalizeSummary(llmClient.generate(prompt), chunk.title(), chunk.content(), "chunk");
    } catch (RuntimeException e) {
      return fallbackSummary(chunk.title(), chunk.content(), "chunk");
    }
  }

  private String buildDocumentPrompt(String title, String content) {
    return """
        Please write a concise summary for the document below.
        Requirements:
        1. Keep it within 80-150 Chinese characters
        2. State the topic, purpose, and key content
        3. Do not invent facts
        4. Output in Chinese

        Title:
        %s
        Content:
        %s
        """
        .formatted(safe(title), safe(content));
  }

  private String buildChunkPrompt(Chunk chunk) {
    return """
        Please write a concise explanation for the code or text block below.
        Requirements:
        1. Describe the responsibility of this class, method, or paragraph
        2. Mention the key inputs, outputs, or topic
        3. Keep it within 50-120 Chinese characters
        4. Do not invent behavior that does not exist

        Title:
        %s
        Content:
        %s
        """
        .formatted(safe(chunk.title()), safe(chunk.content()));
  }

  private String normalizeSummary(String raw, String title, String content, String kind) {
    String cleaned = compact(raw);
    if (cleaned.isBlank() || looksLikePromptEcho(cleaned)) {
      return fallbackSummary(title, content, kind);
    }
    return cleaned;
  }

  private boolean looksLikePromptEcho(String value) {
    String lowered = value.toLowerCase(Locale.ROOT);
    return lowered.contains("please write a concise summary for the document below")
        || lowered.contains("please write a concise explanation for the code or text block below")
        || lowered.contains("title:")
        || lowered.contains("content:");
  }

  private String fallbackSummary(String title, String content, String kind) {
    StringBuilder sb = new StringBuilder();
    if (title != null && !title.isBlank()) {
      sb.append(title.strip()).append(": ");
    }
    String body = compact(content);
    if (body.isBlank()) {
      return sb.length() == 0 ? kind + " summary unavailable" : sb.toString().trim();
    }
    String[] sentences = body.split("(?<=[。！？.!?])\\s+");
    if (sentences.length > 0 && !sentences[0].isBlank()) {
      sb.append(sentences[0].trim());
    } else {
      sb.append(body);
    }
    String value = sb.toString().trim();
    if (value.length() > 180) {
      value = value.substring(0, 180).trim();
    }
    return value;
  }

  private String compact(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("\\s+", " ").trim();
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
