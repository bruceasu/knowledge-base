package me.asu.kb.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only file-level Org metadata supported by the knowledge base. */
public final class OrgMetadataExtractor {

  private static final Pattern TITLE = Pattern.compile("^\\s*#\\+TITLE\\s*:\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FILETAGS = Pattern.compile("^\\s*#\\+FILETAGS\\s*:\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);

  public OrgMetadata extract(String text) {
    if (text == null || text.isBlank()) {
      return new OrgMetadata(Optional.empty(), List.of());
    }

    String title = null;
    Set<String> tags = new LinkedHashSet<>();
    for (String line : text.split("\\R", -1)) {
      if (title == null) {
        Matcher titleMatcher = TITLE.matcher(line);
        if (titleMatcher.matches() && !titleMatcher.group(1).isBlank()) {
          title = titleMatcher.group(1).trim();
        }
      }
      Matcher tagsMatcher = FILETAGS.matcher(line);
      if (tagsMatcher.matches()) {
        tags.addAll(normalizeTags(tagsMatcher.group(1)));
      }
    }
    return new OrgMetadata(Optional.ofNullable(title), List.copyOf(tags));
  }

  private List<String> normalizeTags(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String value = raw.trim();
    String[] candidates = value.startsWith(":") || value.endsWith(":")
        ? value.split(":")
        : value.split("\\s+");
    List<String> normalized = new ArrayList<>();
    for (String candidate : candidates) {
      String tag = candidate.trim().toLowerCase(Locale.ROOT);
      if (!tag.isBlank()) {
        normalized.add(tag);
      }
    }
    return normalized;
  }

  public record OrgMetadata(Optional<String> title, List<String> tags) {}
}
