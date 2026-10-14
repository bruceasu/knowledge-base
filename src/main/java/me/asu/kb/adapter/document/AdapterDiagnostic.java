package me.asu.kb.adapter.document;

import java.util.Objects;
import java.util.Optional;

/** Deterministic diagnostic with an optional source location. */
public record AdapterDiagnostic(AdapterDiagnosticCode code, Optional<SourceRange> sourceRange) {

  public AdapterDiagnostic {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(sourceRange, "sourceRange");
  }

  public static AdapterDiagnostic at(AdapterDiagnosticCode code, int line) {
    return new AdapterDiagnostic(code, Optional.of(new SourceRange(line, line)));
  }

  public static AdapterDiagnostic withoutLocation(AdapterDiagnosticCode code) {
    return new AdapterDiagnostic(code, Optional.empty());
  }
}
