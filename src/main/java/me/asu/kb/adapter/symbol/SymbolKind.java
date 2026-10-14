package me.asu.kb.adapter.symbol;

import java.util.Optional;

/** Java tag kinds confirmed by the fixed Universal Ctags profile. */
public enum SymbolKind {
  ANNOTATION("annotation", false),
  CLASS("class", false),
  ENUM_CONSTANT("enumConstant", true),
  FIELD("field", true),
  ENUM("enum", false),
  INTERFACE("interface", false),
  METHOD("method", true),
  PACKAGE("package", false),
  RECORD("record", false);

  private final String ctagsName;
  private final boolean requiresScope;

  SymbolKind(String ctagsName, boolean requiresScope) {
    this.ctagsName = ctagsName;
    this.requiresScope = requiresScope;
  }

  public String ctagsName() {
    return ctagsName;
  }

  boolean requiresScope() {
    return requiresScope;
  }

  static Optional<SymbolKind> fromCtagsName(String value) {
    for (SymbolKind kind : values()) {
      if (kind.ctagsName.equals(value)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }
}
