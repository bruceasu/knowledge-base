package me.asu.kb.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a bounded allowlist of metadata from an HTML head section. */
public final class HtmlMetadataExtractor {

  private static final int MAX_HEAD_LENGTH = 64 * 1024;
  private static final int MAX_VALUE_LENGTH = 500;
  private static final int MAX_VALUES = 32;
  private static final Pattern HEAD = Pattern.compile("<head\\b[^>]*>(.*?)</head\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern META = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.DOTALL);

  public HtmlMetadata extract(String html) {
    if (html == null || html.isBlank()) {
      return new HtmlMetadata(Optional.empty(), Optional.empty(), List.of(), List.of());
    }
    Matcher headMatcher = HEAD.matcher(html);
    if (!headMatcher.find()) {
      return new HtmlMetadata(Optional.empty(), Optional.empty(), List.of(), List.of());
    }
    String head = headMatcher.group(1);
    if (head.length() > MAX_HEAD_LENGTH) {
      head = head.substring(0, MAX_HEAD_LENGTH);
    }

    String title = null;
    Matcher titleMatcher = TITLE.matcher(head);
    if (titleMatcher.find()) {
      title = cleanValue(titleMatcher.group(1));
    }
    String openGraphTitle = null;
    String description = null;
    String openGraphDescription = null;
    Set<String> keywords = new LinkedHashSet<>();
    Set<String> tags = new LinkedHashSet<>();
    Matcher metaMatcher = META.matcher(head);
    while (metaMatcher.find()) {
      MapAttributes attributes = attributes(metaMatcher.group(1));
      String name = attributes.name();
      String content = cleanValue(attributes.content());
      if (name == null || content == null || content.isBlank()) {
        continue;
      }
      switch (name) {
        case "og:title" -> openGraphTitle = firstValue(openGraphTitle, content);
        case "description" -> description = firstValue(description, content);
        case "og:description" -> openGraphDescription = firstValue(openGraphDescription, content);
        case "keywords" -> addDelimited(keywords, content);
        case "article:tag" -> addValue(tags, content);
        default -> {
          // Deliberately ignore arbitrary HTML metadata.
        }
      }
    }
    return new HtmlMetadata(
        Optional.ofNullable(firstValue(title, openGraphTitle)),
        Optional.ofNullable(firstValue(description, openGraphDescription)),
        List.copyOf(keywords),
        List.copyOf(tags));
  }

  private void addDelimited(Set<String> values, String raw) {
    for (String value : raw.split(",")) {
      addValue(values, value);
    }
  }

  private void addValue(Set<String> values, String raw) {
    if (values.size() >= MAX_VALUES) {
      return;
    }
    String value = cleanValue(raw);
    if (!value.isBlank()) {
      values.add(value.toLowerCase(Locale.ROOT));
    }
  }

  private String firstValue(String current, String candidate) {
    return current == null || current.isBlank() ? candidate : current;
  }

  private String cleanValue(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.replaceAll("(?is)<[^>]*>", "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim();
    return cleaned.length() > MAX_VALUE_LENGTH ? cleaned.substring(0, MAX_VALUE_LENGTH).trim() : cleaned;
  }

  private MapAttributes attributes(String raw) {
    String name = null;
    String content = null;
    Matcher matcher = ATTRIBUTE.matcher(raw);
    while (matcher.find()) {
      String attribute = matcher.group(1).toLowerCase(Locale.ROOT);
      String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
      if (("name".equals(attribute) || "property".equals(attribute)) && name == null) {
        name = value.toLowerCase(Locale.ROOT).trim();
      } else if ("content".equals(attribute) && content == null) {
        content = value;
      }
    }
    return new MapAttributes(name, content);
  }

  private record MapAttributes(String name, String content) {}

  public record HtmlMetadata(
      Optional<String> title, Optional<String> description, List<String> keywords, List<String> tags) {}
}
