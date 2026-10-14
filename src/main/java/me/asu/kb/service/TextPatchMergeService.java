package me.asu.kb.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Performs conservative three-way merges for plain text using line changes. */
public final class TextPatchMergeService {

  /**
   * Merges the submitted editor content with changes made to the server version since base.
   *
   * <p>Only non-overlapping line changes are merged automatically. An overlapping change, including
   * two different insertions at the same position, returns a conflict instead of choosing a side.
   */
  public TextPatchMergeResult merge(String base, String server, String submitted) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(submitted, "submitted");

    if (server.equals(base)) {
      return TextPatchMergeResult.merged(submitted);
    }
    if (submitted.equals(base)) {
      return TextPatchMergeResult.merged(server);
    }
    if (server.equals(submitted)) {
      return TextPatchMergeResult.merged(server);
    }

    List<String> baseLines = lines(base);
    List<Change> serverChanges = changes(baseLines, lines(server));
    List<Change> submittedChanges = changes(baseLines, lines(submitted));
    StringBuilder merged = new StringBuilder();
    int baseCursor = 0;
    int serverIndex = 0;
    int submittedIndex = 0;

    while (serverIndex < serverChanges.size() || submittedIndex < submittedChanges.size()) {
      Change serverChange = current(serverChanges, serverIndex);
      Change submittedChange = current(submittedChanges, submittedIndex);

      if (serverChange != null
          && submittedChange != null
          && overlaps(serverChange, submittedChange)) {
        if (serverChange.equals(submittedChange)) {
          appendBase(merged, baseLines, baseCursor, serverChange.start());
          appendReplacement(merged, serverChange.replacement());
          baseCursor = serverChange.end();
          serverIndex++;
          submittedIndex++;
          continue;
        }
        return TextPatchMergeResult.conflict(base, server, submitted);
      }

      boolean useServer =
          submittedChange == null
              || (serverChange != null && compareByPosition(serverChange, submittedChange) < 0);
      Change next = useServer ? serverChange : submittedChange;
      appendBase(merged, baseLines, baseCursor, next.start());
      appendReplacement(merged, next.replacement());
      baseCursor = next.end();
      if (useServer) {
        serverIndex++;
      } else {
        submittedIndex++;
      }
    }
    appendBase(merged, baseLines, baseCursor, baseLines.size());
    return TextPatchMergeResult.merged(merged.toString());
  }

  private static Change current(List<Change> changes, int index) {
    return index < changes.size() ? changes.get(index) : null;
  }

  private static int compareByPosition(Change left, Change right) {
    int startComparison = Integer.compare(left.start(), right.start());
    if (startComparison != 0) {
      return startComparison;
    }
    return Integer.compare(left.end(), right.end());
  }

  private static boolean overlaps(Change left, Change right) {
    if (left.start() == left.end() && right.start() == right.end()) {
      return left.start() == right.start();
    }
    if (left.start() == left.end()) {
      return left.start() >= right.start() && left.start() <= right.end();
    }
    if (right.start() == right.end()) {
      return right.start() >= left.start() && right.start() <= left.end();
    }
    return left.start() < right.end() && right.start() < left.end();
  }

  private static void appendBase(StringBuilder output, List<String> lines, int start, int end) {
    if (start < end) {
      appendLines(output, lines.subList(start, end));
    }
  }

  private static void appendReplacement(StringBuilder output, List<String> replacement) {
    appendLines(output, replacement);
  }

  private static void appendLines(StringBuilder output, List<String> lines) {
    for (String line : lines) {
      if (output.length() > 0) {
        output.append('\n');
      }
      output.append(line);
    }
  }

  private static List<String> lines(String text) {
    return Arrays.asList(text.split("\\n", -1));
  }

  private static List<Change> changes(List<String> base, List<String> variant) {
    int[][] lcs = new int[base.size() + 1][variant.size() + 1];
    for (int baseIndex = base.size() - 1; baseIndex >= 0; baseIndex--) {
      for (int variantIndex = variant.size() - 1; variantIndex >= 0; variantIndex--) {
        lcs[baseIndex][variantIndex] =
            base.get(baseIndex).equals(variant.get(variantIndex))
                ? lcs[baseIndex + 1][variantIndex + 1] + 1
                : Math.max(lcs[baseIndex + 1][variantIndex], lcs[baseIndex][variantIndex + 1]);
      }
    }

    List<Change> changes = new ArrayList<>();
    int baseIndex = 0;
    int variantIndex = 0;
    while (baseIndex < base.size() || variantIndex < variant.size()) {
      if (baseIndex < base.size()
          && variantIndex < variant.size()
          && base.get(baseIndex).equals(variant.get(variantIndex))) {
        baseIndex++;
        variantIndex++;
        continue;
      }

      int startBase = baseIndex;
      int startVariant = variantIndex;
      while (baseIndex < base.size()
          && variantIndex < variant.size()
          && !base.get(baseIndex).equals(variant.get(variantIndex))) {
        if (lcs[baseIndex][variantIndex + 1] >= lcs[baseIndex + 1][variantIndex]) {
          variantIndex++;
        } else {
          baseIndex++;
        }
      }
      if (baseIndex == base.size()) {
        variantIndex = variant.size();
      } else if (variantIndex == variant.size()) {
        baseIndex = base.size();
      }
      changes.add(
          new Change(
              startBase,
              baseIndex,
              List.copyOf(variant.subList(startVariant, variantIndex))));
    }
    return Collections.unmodifiableList(changes);
  }

  private record Change(int start, int end, List<String> replacement) {}
}
