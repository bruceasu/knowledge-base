package me.asu.kb.adapter.symbol;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;

/**
 * One in-memory static-analysis candidate. It is not a persisted knowledge object or a code
 * relation; its result-level Ctags profile is available from {@link SymbolExtractionResult}.
 */
public record ExtractedSymbol(
    SymbolIdentity identity,
    SymbolKind kind,
    SymbolLanguage language,
    String simpleName,
    Optional<String> qualifiedName,
    Optional<String> signature,
    Optional<String> scope,
    Optional<String> scopeKind,
    String relativePath,
    SymbolSourceRange range,
    Evidence evidence) {

  public ExtractedSymbol {
    identity = Objects.requireNonNull(identity, "identity");
    kind = Objects.requireNonNull(kind, "kind");
    language = Objects.requireNonNull(language, "language");
    simpleName = requireText(simpleName, "simpleName");
    qualifiedName = optionalText(qualifiedName, "qualifiedName");
    signature = optionalText(signature, "signature");
    scope = optionalText(scope, "scope");
    scopeKind = optionalText(scopeKind, "scopeKind");
    relativePath = requireText(relativePath, "relativePath");
    range = Objects.requireNonNull(range, "range");
    evidence = Objects.requireNonNull(evidence, "evidence");
    if (evidence.type() != EvidenceType.STATIC_ANALYSIS) {
      throw new IllegalArgumentException("evidence must be static analysis");
    }
  }

  private static Optional<String> optionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(item -> requireText(item, name));
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
