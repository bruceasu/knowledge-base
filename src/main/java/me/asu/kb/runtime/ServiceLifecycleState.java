package me.asu.kb.runtime;

/** Stable service lifecycle values exposed by readiness and status transports. */
public enum ServiceLifecycleState {
  STARTING,
  READY,
  DEGRADED,
  CLOSING,
  CLOSED,
  FAILED
}
