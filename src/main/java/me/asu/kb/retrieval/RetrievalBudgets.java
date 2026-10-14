package me.asu.kb.retrieval;

import java.time.Duration;

/** Fixed internal ceilings; candidate sources may use smaller adapter-specific limits. */
record RetrievalBudgets(
    int resultLimit,
    int storeCandidates,
    int luceneCandidates,
    int ripgrepCandidates,
    int ctagsCandidates,
    int gitPaths,
    int totalCandidates,
    Duration deadline) {

  static final int DEFAULT_RESULT_LIMIT = 20;
  static final int MAX_RESULT_LIMIT = 100;
  static final int MAX_QUERY_LENGTH = 512;

  static RetrievalBudgets forResultLimit(int resultLimit) {
    return new RetrievalBudgets(
        resultLimit,
        200,
        200,
        100,
        100,
        64,
        500,
        Duration.ofSeconds(5));
  }
}
