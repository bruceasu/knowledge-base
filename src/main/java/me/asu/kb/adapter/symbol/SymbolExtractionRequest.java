package me.asu.kb.adapter.symbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bounded request for explicit Java source files. Semantic validation belongs to
 * {@link CtagsSymbolExtractor#extract(SymbolExtractionRequest)} so invalid input can be
 * reported without launching Ctags.
 */
public record SymbolExtractionRequest(
    String workspaceId, String language, List<String> relativeJavaFiles, int maxResults) {

  public SymbolExtractionRequest {
    relativeJavaFiles =
        relativeJavaFiles == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(relativeJavaFiles));
  }

  /** Creates a Java request that uses the Adapter\'s configured result ceiling. */
  public static SymbolExtractionRequest forJava(String workspaceId, List<String> relativeJavaFiles) {
    return new SymbolExtractionRequest(workspaceId, SymbolLanguage.JAVA.ctagsName(), relativeJavaFiles, 0);
  }
}
