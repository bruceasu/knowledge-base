package me.asu.kb.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/** Transport-local seam implemented by the facade handlers in the next delivery slice. */
@FunctionalInterface
public interface KnowledgeMcpToolHandler {
  McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments);
}
