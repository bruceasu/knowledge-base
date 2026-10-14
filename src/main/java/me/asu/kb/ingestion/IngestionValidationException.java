package me.asu.kb.ingestion;

/** Request-level validation failure that prevents every Adapter and Store invocation. */
final class IngestionValidationException extends IllegalArgumentException {
  private final IngestionDiagnosticCode code;

  IngestionValidationException(IngestionDiagnosticCode code, String message) {
    super(message);
    this.code = code;
  }

  IngestionDiagnosticCode code() {
    return code;
  }
}
