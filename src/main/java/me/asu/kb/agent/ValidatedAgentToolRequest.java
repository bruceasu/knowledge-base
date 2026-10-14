package me.asu.kb.agent;

import java.util.Objects;
import me.asu.kb.workspace.EngineeringWorkspace;

/** One schema-decoded request bound to a registered workspace and active call deadline. */
public record ValidatedAgentToolRequest(
    String toolName, Object request, EngineeringWorkspace workspace, AgentCallDeadline deadline) {
  public ValidatedAgentToolRequest {
    toolName = Objects.requireNonNull(toolName, "toolName");
    request = Objects.requireNonNull(request, "request");
    workspace = Objects.requireNonNull(workspace, "workspace");
    deadline = Objects.requireNonNull(deadline, "deadline");
  }

  public <T> T requestAs(Class<T> requestType) {
    return requestType.cast(request);
  }
}
