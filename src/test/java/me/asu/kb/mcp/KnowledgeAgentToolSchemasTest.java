package me.asu.kb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeAgentToolSchemasTest {
  @Test
  void exposesExactlyTheSixVersionedReadOnlyContracts() {
    assertEquals(
        Set.of("knowledge_search", "knowledge_get", "code_symbol", "code_search", "trace", "build_context"),
        KnowledgeAgentToolSchemas.all().keySet());
    KnowledgeAgentToolSchemas.all().values().forEach(schema -> {
      assertEquals("1.0", schema.schemaVersion());
      assertRecursivelyStrict(schema.input());
      assertRecursivelyStrict(schema.output());
      assertFalse(schema.input().toString().contains("workspaceId"));
      assertFalse(schema.output().toString().contains("workspaceId"));
      assertTrue(KnowledgeAgentToolSchemas.description(schema.name()).length() > 80);
    });
    assertThrows(UnsupportedOperationException.class, () -> KnowledgeAgentToolSchemas.all().clear());
  }

  @Test
  void descriptionsAreSpecificAndDoNotPromiseWritesOrNetworkAccess() {
    KnowledgeAgentToolSchemas.all().keySet().forEach(tool -> {
      String description = KnowledgeAgentToolSchemas.description(tool).toLowerCase();
      assertFalse(description.contains("write to"));
      assertFalse(description.contains("network"));
      assertFalse(description.contains("arbitrary"));
    });
  }

  @Test
  void fixesPublicBudgetsAndDoesNotExposeArbitraryTraceDepth() {
    assertEquals(100, property("knowledge_search", "resultLimit").get("maximum"));
    assertEquals(100, property("code_symbol", "resultLimit").get("maximum"));
    assertEquals(200, property("code_search", "maxResults").get("maximum"));
    assertEquals(3, property("code_search", "contextLines").get("maximum"));
    assertEquals(32768, property("build_context", "tokenBudget").get("maximum"));
    assertEquals(256, property("build_context", "tokenBudget").get("minimum"));
    assertEquals(
        List.of("UNDERSTAND", "CHANGE", "REVIEW", "DIAGNOSE", "TEST"),
        property("build_context", "intent").get("enum"));
    assertFalse(properties("trace").containsKey("depth"));
    assertFalse(properties("trace").containsKey("query"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(String tool) {
    return (Map<String, Object>) KnowledgeAgentToolSchemas.require(tool).input().get("properties");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> property(String tool, String name) {
    return (Map<String, Object>) properties(tool).get(name);
  }

  @SuppressWarnings("unchecked")
  private static void assertRecursivelyStrict(Map<String, Object> schema) {
    if ("object".equals(schema.get("type"))) {
      assertEquals(false, schema.get("additionalProperties"));
      ((Map<String, Object>) schema.get("properties"))
          .values().forEach(value -> assertRecursivelyStrict((Map<String, Object>) value));
    } else if ("array".equals(schema.get("type"))) {
      assertRecursivelyStrict((Map<String, Object>) schema.get("items"));
    }
  }
}
