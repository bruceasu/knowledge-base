package me.asu.kb.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Operator-safe readiness view. It deliberately contains no workspace path. */
public record ServiceReadiness(
    String schemaVersion,
    boolean ready,
    ServiceLifecycleState state,
    String reason,
    Map<String, ServiceLifecycleState> components,
    int queueDepth,
    Freshness freshness) {
  public static final String SCHEMA_VERSION = "1";

  public ServiceReadiness {
    schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
    state = Objects.requireNonNull(state, "state");
    components = Map.copyOf(Objects.requireNonNull(components, "components"));
    freshness = Objects.requireNonNull(freshness, "freshness");
    if (queueDepth < 0) {
      throw new IllegalArgumentException("queueDepth must not be negative");
    }
  }

  /** Projection freshness is populated by the application status integration in Work Item 2.1. */
  public record Freshness(
      String businessCheckpoint,
      String projectionCheckpoint,
      Instant lastSuccessfulProjectionCommitAt) {}
}
