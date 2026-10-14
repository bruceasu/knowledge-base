package me.asu.kb.adapter.codesearch;

/** Categories which let callers distinguish an empty search from incomplete or failed work. */
public enum CodeSearchDiagnosticCode {
  INVALID_REQUEST,
  EMPTY_QUERY,
  UNKNOWN_WORKSPACE,
  INVALID_SCOPE,
  REQUEST_LIMIT_EXCEEDED,
  TOOL_UNAVAILABLE,
  UNEXPECTED_EXIT,
  TIMEOUT,
  MALFORMED_OUTPUT,
  ENCODING_ERROR,
  OUTPUT_TRUNCATED,
  RESULT_TRUNCATED,
  EXCERPT_TRUNCATED
}
