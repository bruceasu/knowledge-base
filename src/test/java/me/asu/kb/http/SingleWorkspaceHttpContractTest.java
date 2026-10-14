package me.asu.kb.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SingleWorkspaceHttpContractTest {
  @Test
  void identifiesEveryObsoleteWorkspaceRoutingLocation() {
    assertEquals(
        "workspaceId",
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of("workspaceId"), Set.of(), Set.of(), false, false));
    assertEquals(
        "workspace",
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of(), Set.of("workspace"), Set.of(), false, false));
    assertEquals(
        "workspace_id",
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of(), Set.of(), Set.of("workspace_id"), false, false));
    assertEquals(
        "X-Workspace-Id",
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of(), Set.of(), Set.of(), true, false));
    assertEquals(
        "X-Workspace",
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of(), Set.of(), Set.of(), false, true));
    assertNull(
        SingleWorkspaceHttpApp.obsoleteWorkspaceField(
            Set.of("query"), Set.of("title"), Set.of("target"), false, false));
  }

  @Test
  @SuppressWarnings("unchecked")
  void removesInternalWorkspaceRoutingFromNestedResponses() {
    Object cleaned =
        SingleWorkspaceHttpApp.workspaceFreePayload(
            Map.of(
                "workspaceId", "service",
                "data", Map.of("workspace", "service", "items", List.of(Map.of("id", "x"))),
                "ok", true));

    Map<String, Object> root = (Map<String, Object>) cleaned;
    assertFalse(root.containsKey("workspaceId"));
    Map<String, Object> data = (Map<String, Object>) root.get("data");
    assertFalse(data.containsKey("workspace"));
    assertEquals("x", ((Map<?, ?>) ((List<?>) data.get("items")).get(0)).get("id"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void serializesInstantAsIso8601TransportValue() {
    Instant createdAt = Instant.parse("2026-09-23T00:00:00Z");

    Map<String, Object> payload =
        (Map<String, Object>) SingleWorkspaceHttpApp.workspaceFreePayload(
            Map.of("createdAt", createdAt));

    assertEquals("2026-09-23T00:00:00Z", payload.get("createdAt"));
  }
}
