package me.asu.kb.adapter.symbol;

import java.util.Optional;

/** Languages whose Ctags records this Adapter has explicitly normalized. */
public enum SymbolLanguage {
  JAVA("Java");

  private final String ctagsName;

  SymbolLanguage(String ctagsName) {
    this.ctagsName = ctagsName;
  }

  public String ctagsName() {
    return ctagsName;
  }

  static Optional<SymbolLanguage> fromCtagsName(String value) {
    for (SymbolLanguage language : values()) {
      if (language.ctagsName.equals(value)) {
        return Optional.of(language);
      }
    }
    return Optional.empty();
  }
}
