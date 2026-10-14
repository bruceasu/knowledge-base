package me.asu.kb.ingestion;

import java.util.Objects;
import me.asu.kb.adapter.symbol.VerifiedSymbolExtraction;

/** One Java file selected from a verified multi-file Ctags extraction result. */
public record JavaSymbolIngestionUnit(
    String stableSourceId,
    String workspaceRelativePath,
    VerifiedSymbolExtraction extraction)
    implements IngestionSourceUnit {

  public JavaSymbolIngestionUnit {
    stableSourceId = requireText(stableSourceId, "stableSourceId");
    workspaceRelativePath = requireText(workspaceRelativePath, "workspaceRelativePath");
    extraction = Objects.requireNonNull(extraction, "extraction");
  }

  @Override
  public IngestionSourceCategory category() {
    return IngestionSourceCategory.JAVA_SYMBOLS;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
