package me.asu.kb.agent;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, infrastructure-free values shared by the agent facade and its transport adapters. */
public final class AgentToolValues {
  public static final String SCHEMA_VERSION = "1.0";

  private AgentToolValues() {}

  public record KnowledgeSearchRequest(
      String workspaceId,
      String query,
      Set<String> objectTypes,
      int resultLimit,
      boolean includeStale,
      boolean requireEvidence,
      String virtualProjectId) {
    /** Backward-compatible shared-scope request constructor. */
    public KnowledgeSearchRequest(
        String workspaceId,
        String query,
        Set<String> objectTypes,
        int resultLimit,
        boolean includeStale,
        boolean requireEvidence) {
      this(workspaceId, query, objectTypes, resultLimit, includeStale, requireEvidence, "");
    }

    public KnowledgeSearchRequest {
      objectTypes = objectTypes == null ? Set.of() : Set.copyOf(objectTypes);
      virtualProjectId = virtualProjectId == null ? "" : virtualProjectId.trim();
    }
  }

  public record KnowledgeGetRequest(String workspaceId, String objectId) {}

  public record CodeSymbolRequest(String workspaceId, String query, int resultLimit) {}

  public record CodeSearchRequest(
      String workspaceId,
      String literalQuery,
      List<String> relativeScopes,
      int contextLines,
      int maxResults) {
    public CodeSearchRequest {
      relativeScopes = relativeScopes == null ? List.of() : List.copyOf(relativeScopes);
    }
  }

  public record TraceRequest(String workspaceId, String startObjectId) {}

  public record BuildContextRequest(
      String workspaceId,
      String target,
      String intent,
      int tokenBudget,
      boolean includeStale,
      boolean requireEvidence) {}

  public enum Status {
    OK,
    INVALID_ARGUMENT,
    NOT_FOUND,
    UNAVAILABLE,
    INCOMPLETE,
    DEADLINE_EXCEEDED,
    CANCELLED,
    INTERNAL
  }

  public record Diagnostic(String code, String detail) {}

  public record ToolResponse<T>(
      String schemaVersion,
      Status status,
      boolean complete,
      T data,
      List<Diagnostic> diagnostics) {
    public ToolResponse {
      schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
      status = Objects.requireNonNull(status, "status");
      data = Objects.requireNonNull(data, "data");
      diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
  }

  public record Source(String sourceId, String relativePath, Integer startLine, Integer endLine) {}

  public record Evidence(
      String type,
      String sourceId,
      String relativePath,
      Integer startLine,
      Integer endLine,
      String excerpt) {}

  public record KnowledgeItem(
      String objectId,
      String objectType,
      String title,
      String excerpt,
      Source source,
      String scope,
      String virtualProjectId,
      String freshness,
      List<String> provenance,
      List<Evidence> evidence) {
    /** Backward-compatible shared-scope result constructor. */
    public KnowledgeItem(
        String objectId,
        String objectType,
        String title,
        String excerpt,
        Source source,
        String freshness,
        List<String> provenance,
        List<Evidence> evidence) {
      this(objectId, objectType, title, excerpt, source, "SHARED", null, freshness, provenance, evidence);
    }

    public KnowledgeItem {
      scope = scope == null || scope.isBlank() ? "SHARED" : scope;
      if (virtualProjectId != null && virtualProjectId.isBlank()) {
        virtualProjectId = null;
      }
      provenance = provenance == null ? List.of() : List.copyOf(provenance);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record KnowledgeSearchData(String workspaceId, List<KnowledgeItem> items) {
    public KnowledgeSearchData {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record KnowledgeGetData(String workspaceId, KnowledgeItem object) {}

  public record CodeSymbolData(String workspaceId, List<KnowledgeItem> symbols) {
    public CodeSymbolData {
      symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }
  }

  public record CodeMatch(
      String relativePath,
      int startLine,
      int endLine,
      String excerpt,
      boolean excerptTruncated,
      String matchedText,
      Evidence evidence) {}

  public record CodeSearchData(String workspaceId, List<CodeMatch> matches, boolean truncated) {
    public CodeSearchData {
      matches = matches == null ? List.of() : List.copyOf(matches);
    }
  }

  public record TraceObject(
      String objectId, String objectType, String title, Source source, String freshness) {}

  public record TracedRelation(
      String relationId,
      TraceObject source,
      String relationType,
      TraceObject target,
      List<Evidence> evidence) {
    public TracedRelation {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record TraceData(
      String workspaceId, TraceObject startObject, List<TracedRelation> relations) {
    public TraceData {
      relations = relations == null ? List.of() : List.copyOf(relations);
    }
  }

  public record ContextItem(
      String objectId,
      String objectType,
      String title,
      String fragment,
      Source source,
      String freshness,
      List<String> provenance,
      List<Evidence> evidence,
      String selectionReason,
      boolean truncated,
      int estimatedTokens) {
    public ContextItem {
      provenance = provenance == null ? List.of() : List.copyOf(provenance);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record Omission(String objectId, String reason, String detail) {}

  public record BuildContextData(
      String workspaceId,
      String target,
      String intent,
      List<ContextItem> items,
      int estimatedTokens,
      String estimatorVersion,
      List<Omission> omissions) {
    public BuildContextData {
      items = items == null ? List.of() : List.copyOf(items);
      omissions = omissions == null ? List.of() : List.copyOf(omissions);
    }
  }
}
