package me.asu.kb.ingest;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

  public enum Format {
    PLAIN,
    MARKDOWN,
    HTML,
    ORG
  }

  public List<Chunk> split(String text) {
    return split(text, Format.PLAIN);
  }

  public List<Chunk> split(String text, Format format) {
    if (text == null || text.isBlank()) {
      return new ArrayList<>();
    }

    return switch (format == null ? Format.PLAIN : format) {
      case PLAIN -> splitByBlankLines(text);
      case MARKDOWN -> splitMarkdown(text);
      case ORG -> splitByHeadings(text, this::isOrgHeading, this::orgTitle, true);
      case HTML ->
          splitByHeadings(
              normalizeHtml(text),
              new HeadingMatcher() {
                @Override
                public boolean isHeading(String line) {
                  return isMarkdownHeading(line);
                }

                @Override
                public String cleanHeading(String line) {
                  return markdownTitle(line);
                }
              },
              this::markdownTitle,
              false);
    };
  }

  private List<Chunk> splitByBlankLines(String text) {
    List<Chunk> chunks = new ArrayList<>();
    String[] parts = text.split("\\R\\s*\\R+");
    int chunkNo = 0;
    int line = 1;
    for (String raw : parts) {
      String content = raw.strip();
      if (content.isEmpty()) {
        continue;
      }
      int lines = Math.max(1, content.split("\\R", -1).length);
      chunks.add(new Chunk(chunkNo++, extractTitle(content), content, line, line + lines - 1));
      line += lines;
    }
    return chunks;
  }

  private List<Chunk> splitMarkdown(String text) {
    List<Chunk> chunks = new ArrayList<>();
    List<String> buffer = new ArrayList<>();
    int chunkNo = 0;
    int chunkStartLine = 1;
    int lineNo = 1;
    boolean inFence = false;
    String fenceMarker = null;

    String[] lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].stripTrailing();
      String trimmed = line.stripLeading();
      String nextLine = i + 1 < lines.length ? lines[i + 1].stripTrailing() : null;

      if (isFenceLine(trimmed)) {
        if (!inFence) {
          inFence = true;
          fenceMarker = fenceMarker(trimmed);
        } else if (trimmed.startsWith(fenceMarker)) {
          inFence = false;
          fenceMarker = null;
        }
      }

      if (!inFence && isSetextUnderline(nextLine) && !line.isBlank()) {
        if (!buffer.isEmpty()) {
          chunks.add(
              new Chunk(
                  chunkNo++,
                  extractTitle(buffer, this::markdownTitle),
                  join(buffer),
                  chunkStartLine,
                  lineNo - 1));
          buffer = new ArrayList<>();
        }
        if (buffer.isEmpty()) {
          chunkStartLine = lineNo;
        }
        buffer.add(line);
        buffer.add(nextLine);
        i++;
        lineNo += 2;
        continue;
      }

      if (!inFence && isMarkdownHeading(line) && !buffer.isEmpty()) {
        chunks.add(
            new Chunk(
                chunkNo++,
                extractTitle(buffer, this::markdownTitle),
                join(buffer),
                chunkStartLine,
                lineNo - 1));
        buffer = new ArrayList<>();
      }

      if (buffer.isEmpty()) {
        chunkStartLine = lineNo;
      }
      buffer.add(line);
      lineNo++;
    }

    if (!buffer.isEmpty()) {
      chunks.add(
          new Chunk(
              chunkNo++,
              extractTitle(buffer, this::markdownTitle),
              join(buffer),
              chunkStartLine,
              lineNo - 1));
    }
    return chunks;
  }

  private List<Chunk> splitByHeadings(
      String text,
      HeadingMatcher headingMatcher,
      HeadingTitleExtractor titleExtractor,
      boolean preserveHeadingMarkers) {
    List<Chunk> chunks = new ArrayList<>();
    List<String> buffer = new ArrayList<>();
    int chunkNo = 0;
    int chunkStartLine = 1;
    int lineNo = 1;
    for (String rawLine : text.split("\\R", -1)) {
      String line = preserveHeadingMarkers ? rawLine.stripTrailing() : rawLine.strip();
      if (rawLine.isBlank()) {
        if (!preserveHeadingMarkers) {
          lineNo++;
          continue;
        }
        if (!buffer.isEmpty() && !buffer.get(buffer.size() - 1).isEmpty()) {
          buffer.add("");
        }
        lineNo++;
        continue;
      }

      if (headingMatcher.isHeading(line) && !buffer.isEmpty()) {
        chunks.add(
            new Chunk(
                chunkNo++,
                extractTitle(buffer, titleExtractor),
                join(buffer),
                chunkStartLine,
                lineNo - 1));
        buffer = new ArrayList<>();
      }

      if (buffer.isEmpty()) {
        chunkStartLine = lineNo;
      }
      buffer.add(preserveHeadingMarkers ? line : headingMatcher.cleanHeading(line));
      lineNo++;
    }

    if (!buffer.isEmpty()) {
      chunks.add(
          new Chunk(
              chunkNo++,
              extractTitle(buffer, titleExtractor),
              join(buffer),
              chunkStartLine,
              lineNo - 1));
    }
    return chunks;
  }

  private String normalizeHtml(String html) {
    String text = html;
    text = text.replaceAll("(?is)<!--.*?-->", "");
    text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
    text = text.replaceAll("(?i)<br\\s*/?>", "\n");
    text = text.replaceAll("(?i)</?(p|div|section|article|header|footer|blockquote|pre|ul|ol|table|tbody|thead|tr|td|th)\\b[^>]*>", "\n");
    text = text.replaceAll("(?i)<h1\\b[^>]*>", "\n# ");
    text = text.replaceAll("(?i)</h1>", "\n");
    text = text.replaceAll("(?i)<h2\\b[^>]*>", "\n## ");
    text = text.replaceAll("(?i)</h2>", "\n");
    text = text.replaceAll("(?i)<h3\\b[^>]*>", "\n### ");
    text = text.replaceAll("(?i)</h3>", "\n");
    text = text.replaceAll("(?i)<h4\\b[^>]*>", "\n#### ");
    text = text.replaceAll("(?i)</h4>", "\n");
    text = text.replaceAll("(?i)<h5\\b[^>]*>", "\n##### ");
    text = text.replaceAll("(?i)</h5>", "\n");
    text = text.replaceAll("(?i)<h6\\b[^>]*>", "\n###### ");
    text = text.replaceAll("(?i)</h6>", "\n");
    text = text.replaceAll("(?i)<li\\b[^>]*>", "\n- ");
    text = text.replaceAll("(?i)</li>", "\n");
    text = text.replaceAll("(?is)<[^>]+>", "");
    text = text.replace("&nbsp;", " ");
    return text;
  }

  private boolean isMarkdownHeading(String line) {
    return line.matches("#{1,6}\\s+.+");
  }

  private boolean isOrgHeading(String line) {
    return line.matches("\\*+\\s+.+");
  }

  private boolean isFenceLine(String line) {
    return line.startsWith("```") || line.startsWith("~~~");
  }

  private boolean isSetextUnderline(String line) {
    if (line == null) {
      return false;
    }
    return line.matches("={3,}\\s*") || line.matches("-{3,}\\s*");
  }

  private String fenceMarker(String line) {
    if (line.startsWith("~~~")) {
      return "~~~";
    }
    return "```";
  }

  private String markdownTitle(String line) {
    return line.replaceFirst("^#+\\s*", "").trim();
  }

  private String orgTitle(String line) {
    return line.replaceFirst("^\\*+\\s*", "").trim();
  }

  private String extractTitle(String content) {
    String firstLine = content.lines().findFirst().orElse("").strip();
    if (isMarkdownHeading(firstLine)) {
      return markdownTitle(firstLine);
    }
    if (isOrgHeading(firstLine)) {
      return orgTitle(firstLine);
    }
    return null;
  }

  private String extractTitle(List<String> lines, HeadingTitleExtractor extractor) {
    if (lines.isEmpty()) {
      return null;
    }
    String firstLine = lines.get(0).strip();
    if (firstLine.isBlank()) {
      return null;
    }
    return extractor.extract(firstLine);
  }

  private String join(List<String> lines) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(lines.get(i));
    }
    return sb.toString().stripTrailing();
  }

  private interface HeadingMatcher {
    boolean isHeading(String line);

    default String cleanHeading(String line) {
      return line;
    }
  }

  private interface HeadingTitleExtractor {
    String extract(String line);
  }
}
