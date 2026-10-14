package me.asu.kb.adapter.symbol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A Ctags result bound to extractor-verified SHA-256 hashes of its requested source files. */
public record VerifiedSymbolExtraction(
    SymbolExtractionResult result, Map<String, String> sourceContentHashes) {

  public VerifiedSymbolExtraction {
    result = Objects.requireNonNull(result, "result");
    Objects.requireNonNull(sourceContentHashes, "sourceContentHashes");
    Map<String, String> copy = new LinkedHashMap<>();
    sourceContentHashes.forEach(
        (path, hash) -> copy.put(requireText(path, "source path"), requireSha256(hash)));
    sourceContentHashes = Map.copyOf(copy);
  }

  private static String requireSha256(String value) {
    requireText(value, "source content hash");
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("source content hash must be lowercase SHA-256");
    }
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
