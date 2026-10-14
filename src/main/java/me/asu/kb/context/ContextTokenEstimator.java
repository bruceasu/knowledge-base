package me.asu.kb.context;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;

/** Deterministic local estimator for the fixed source-located context rendering. */
final class ContextTokenEstimator {
  static final String VERSION = "utf8-bytes-v1";
  static final int MINIMUM_BUDGET = 256;

  private ContextTokenEstimator() {}

  static int estimate(
      ObjectType objectType,
      Optional<String> objectId,
      SourceRef source,
      Optional<ContextSourceRange> sourceRange,
      String fragment) {
    return render(objectType, objectId, source, sourceRange, fragment)
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  static int estimateHeader(
      ObjectType objectType,
      Optional<String> objectId,
      SourceRef source,
      Optional<ContextSourceRange> sourceRange) {
    return (renderHeader(objectType, objectId, source, sourceRange) + "\n")
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  static int total(List<ContextItem> items) {
    Objects.requireNonNull(items, "items");
    int total = 0;
    for (ContextItem item : items) {
      total = Math.addExact(total, Objects.requireNonNull(item, "item").estimatedTokens());
    }
    return total;
  }

  static String render(
      ObjectType objectType,
      Optional<String> objectId,
      SourceRef source,
      Optional<ContextSourceRange> sourceRange,
      String fragment) {
    Objects.requireNonNull(fragment, "fragment");
    return renderHeader(objectType, objectId, source, sourceRange)
        + "\n"
        + normalizeLineEndings(fragment);
  }

  private static String renderHeader(
      ObjectType objectType,
      Optional<String> objectId,
      SourceRef source,
      Optional<ContextSourceRange> sourceRange) {
    Objects.requireNonNull(objectType, "objectType");
    Objects.requireNonNull(objectId, "objectId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(sourceRange, "sourceRange");
    String range =
        sourceRange
            .map(value -> value.startLine() + ":" + value.endLine())
            .orElse("-:-");
    return "type="
        + objectType.name()
        + " id="
        + objectId.orElse("-")
        + " source="
        + source.system()
        + ":"
        + source.locator()
        + " revision="
        + source.revision().orElse("-")
        + " range="
        + range;
  }

  static String truncateToUtf8Bytes(String fragment, int maximumBytes) {
    if (maximumBytes < 0) {
      throw new IllegalArgumentException("maximumBytes must not be negative");
    }
    String normalized = normalizeLineEndings(Objects.requireNonNull(fragment, "fragment"));
    int offset = 0;
    int bytes = 0;
    while (offset < normalized.length()) {
      int codePoint = normalized.codePointAt(offset);
      String value = new String(Character.toChars(codePoint));
      int codePointBytes = value.getBytes(StandardCharsets.UTF_8).length;
      if (codePointBytes > maximumBytes - bytes) {
        break;
      }
      bytes += codePointBytes;
      offset += Character.charCount(codePoint);
    }
    return normalized.substring(0, offset);
  }

  static String normalizeLineEndings(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }
}
