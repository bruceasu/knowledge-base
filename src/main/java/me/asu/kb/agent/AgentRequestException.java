package me.asu.kb.agent;

import java.util.Objects;

/** Controlled request-boundary failure safe to map to an MCP tool result. */
public final class AgentRequestException extends Exception {
  private final AgentToolValues.Status status;
  private final String code;

  public AgentRequestException(AgentToolValues.Status status, String code, String message) {
    super(message);
    this.status = Objects.requireNonNull(status, "status");
    this.code = requireText(code, "code");
  }

  public AgentToolValues.Status status() {
    return status;
  }

  public String code() {
    return code;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
