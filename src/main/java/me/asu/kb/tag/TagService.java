package me.asu.kb.tag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TagService {

  public List<String> generateSystemTags(String fileType, String path) {
    Set<String> tags = new LinkedHashSet<>();
    String normalizedType = normalize(fileType);
    switch (normalizedType) {
      case "md", "markdown" -> {
        tags.add("markdown");
        tags.add("doc");
      }
      case "txt" -> {
        tags.add("txt");
        tags.add("doc");
      }
      case "java" -> {
        tags.add("code");
        tags.add("java");
      }
      default -> {
        if (!normalizedType.isBlank()) {
          tags.add(normalizedType);
        }
      }
    }
    return new ArrayList<>(tags);
  }

  public List<String> generatePathTags(String path) {
    Set<String> tags = new LinkedHashSet<>();
    if (path == null || path.isBlank()) {
      return new ArrayList<>(tags);
    }

    Path normalized = Path.of(path).normalize();
    for (Path part : normalized) {
      String segment = normalize(part.toString());
      if (segment.contains("aws")) {
        tags.add("aws");
      }
      if (segment.contains("salesforce")) {
        tags.add("salesforce");
      }
      if (segment.contains("deploy")) {
        tags.add("deployment");
      }
      if (segment.contains("note")) {
        tags.add("note");
      }
      if (segment.contains("code")) {
        tags.add("codebase");
      }
    }
    return new ArrayList<>(tags);
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }
}
