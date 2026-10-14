package me.asu.kb.mcp;

import static java.util.Map.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.asu.kb.agent.AgentToolValues;

/** Versioned JSON Schema contracts for the initial, fixed read-only MCP tool set. */
public final class KnowledgeAgentToolSchemas {
  public static final String VERSION = AgentToolValues.SCHEMA_VERSION;

  private static final Map<String, ToolSchema> SCHEMAS = createSchemas();
  private static final Map<String, String> DESCRIPTIONS = createDescriptions();

  private KnowledgeAgentToolSchemas() {}

  public static Map<String, ToolSchema> all() {
    return SCHEMAS;
  }

  public static ToolSchema require(String toolName) {
    ToolSchema schema = SCHEMAS.get(toolName);
    if (schema == null) {
      throw new IllegalArgumentException("unknown knowledge tool: " + toolName);
    }
    return schema;
  }

  public static String description(String toolName) {
    String description = DESCRIPTIONS.get(toolName);
    if (description == null) {
      throw new IllegalArgumentException("unknown knowledge tool: " + toolName);
    }
    return description;
  }

  public record ToolSchema(
      String name, String schemaVersion, Map<String, Object> input, Map<String, Object> output) {
    public ToolSchema {
      input = Map.copyOf(input);
      output = Map.copyOf(output);
    }
  }

  private static Map<String, ToolSchema> createSchemas() {
    Map<String, ToolSchema> schemas = new LinkedHashMap<>();
    add(schemas, "knowledge_search", knowledgeSearchInput(), response(searchData()));
    add(schemas, "knowledge_get", knowledgeGetInput(), response(getData()));
    add(schemas, "code_symbol", codeSymbolInput(), response(symbolData()));
    add(schemas, "code_search", codeSearchInput(), response(codeSearchData()));
    add(schemas, "trace", traceInput(), response(traceData()));
    add(schemas, "build_context", buildContextInput(), response(contextData()));
    return java.util.Collections.unmodifiableMap(schemas);
  }

  private static Map<String, String> createDescriptions() {
    Map<String, String> descriptions = new LinkedHashMap<>();
    descriptions.put(
        "knowledge_search",
        "Search traceable engineering knowledge. Use for concepts, requirements, designs, tests, commits, or configuration; provide query and optional objectTypes, freshness/evidence filters, resultLimit, or virtualProjectId. Results are bounded and include source, provenance, freshness, and evidence.");
    descriptions.put(
        "knowledge_get",
        "Read one knowledge object by stable objectId. Use after knowledge_search or when a caller already has an object identity; returns the current object with its source and evidence, without changing workspace data.");
    descriptions.put(
        "code_symbol",
        "Find engineering code symbols by name or concept. Use for classes, methods, functions, or other indexed symbols; results are bounded knowledge items with source locations rather than raw Ctags output.");
    descriptions.put(
        "code_search",
        "Search literal text in workspace code with bounded relative scopes and context lines. Use when exact source text is needed; the response can be truncated and reports that state explicitly.");
    descriptions.put(
        "trace",
        "Trace fixed-range, evidence-bearing relations from a stable knowledge object. Use to inspect verified dependencies or references; it does not accept arbitrary graph depth or infer unsupported relations.");
    descriptions.put(
        "build_context",
        "Assemble a bounded, source-aware engineering context for an UNDERSTAND, CHANGE, REVIEW, DIAGNOSE, or TEST intent. Use before reasoning or implementation; returns selected fragments, provenance, evidence, token estimates, and omissions without generating an answer.");
    return java.util.Collections.unmodifiableMap(descriptions);
  }

  private static void add(
      Map<String, ToolSchema> schemas,
      String name,
      Map<String, Object> input,
      Map<String, Object> output) {
    schemas.put(name, new ToolSchema(name, VERSION, input, output));
  }

  private static Map<String, Object> knowledgeSearchInput() {
    return object(
        List.of("query"),
        Map.ofEntries(
            entry("query", text(1, 512)),
            entry("objectTypes", array(enumText(objectTypes()), 0, 32)),
            entry("resultLimit", integer(1, 100)),
            entry("includeStale", bool()),
            entry("requireEvidence", bool()),
            entry("virtualProjectId", text(1, 256))));
  }

  private static Map<String, Object> knowledgeGetInput() {
    return object(
        List.of("objectId"), Map.of("objectId", text(1, 256)));
  }

  private static Map<String, Object> codeSymbolInput() {
    return object(
        List.of("query"),
        Map.of(
            "query", text(1, 512),
            "resultLimit", integer(1, 100)));
  }

  private static Map<String, Object> codeSearchInput() {
    return object(
        List.of("literalQuery"),
        Map.of(
            "literalQuery", text(1, 512),
            "relativeScopes", array(text(1, 512), 0, 64),
            "contextLines", integer(0, 3),
            "maxResults", integer(1, 200)));
  }

  private static Map<String, Object> traceInput() {
    return object(
        List.of("startObjectId"), Map.of("startObjectId", text(1, 256)));
  }

  private static Map<String, Object> buildContextInput() {
    return object(
        List.of("target", "intent", "tokenBudget"),
        Map.of(
            "target", text(1, 512),
            "intent", enumText(List.of("UNDERSTAND", "CHANGE", "REVIEW", "DIAGNOSE", "TEST")),
            "tokenBudget", integer(256, 32768),
            "includeStale", bool(),
            "requireEvidence", bool()));
  }

  private static Map<String, Object> response(Map<String, Object> data) {
    return object(
        List.of("schemaVersion", "status", "complete", "data", "diagnostics"),
        Map.of(
            "schemaVersion", Map.of("type", "string", "const", VERSION),
            "status", enumText(List.of("OK", "INVALID_ARGUMENT", "NOT_FOUND", "UNAVAILABLE", "INCOMPLETE", "DEADLINE_EXCEEDED", "CANCELLED", "INTERNAL")),
            "complete", bool(),
            "data", data,
            "diagnostics", array(diagnostic(), 0, 128)));
  }

  private static Map<String, Object> searchData() {
    return object(List.of("items"), Map.of("items", array(knowledgeItem(), 0, 100)));
  }

  private static Map<String, Object> getData() {
    return object(List.of(), Map.of("object", knowledgeItem()));
  }

  private static Map<String, Object> symbolData() {
    return object(List.of("symbols"), Map.of("symbols", array(knowledgeItem(), 0, 100)));
  }

  private static Map<String, Object> codeSearchData() {
    Map<String, Object> match = object(
        List.of("relativePath", "startLine", "endLine", "excerpt", "excerptTruncated", "matchedText", "evidence"),
        Map.of("relativePath", text(1, 1024), "startLine", integer(1, Integer.MAX_VALUE), "endLine", integer(1, Integer.MAX_VALUE), "excerpt", text(0, 16384), "excerptTruncated", bool(), "matchedText", text(1, 4096), "evidence", evidence()));
    return object(List.of("matches", "truncated"), Map.of("matches", array(match, 0, 200), "truncated", bool()));
  }

  private static Map<String, Object> traceData() {
    Map<String, Object> relation = object(
        List.of("relationId", "source", "relationType", "target", "evidence"),
        Map.of("relationId", text(1, 256), "source", traceObject(), "relationType", text(1, 128), "target", traceObject(), "evidence", array(evidence(), 1, 128)));
    return object(List.of("relations"), Map.of("startObject", traceObject(), "relations", array(relation, 0, 64)));
  }

  private static Map<String, Object> contextData() {
    Map<String, Object> item = object(
        List.of("objectType", "title", "fragment", "source", "freshness", "provenance", "evidence", "selectionReason", "truncated", "estimatedTokens"),
        Map.ofEntries(entry("objectId", text(1, 256)), entry("objectType", text(1, 128)), entry("title", text(1, 1024)), entry("fragment", text(0, 65536)), entry("source", source()), entry("freshness", text(1, 64)), entry("provenance", array(text(1, 128), 0, 32)), entry("evidence", array(evidence(), 0, 128)), entry("selectionReason", text(1, 128)), entry("truncated", bool()), entry("estimatedTokens", integer(0, 32768))));
    Map<String, Object> omission = object(List.of("reason", "detail"), Map.of("objectId", text(1, 256), "reason", text(1, 128), "detail", text(0, 4096)));
    return object(
        List.of("target", "intent", "items", "estimatedTokens", "estimatorVersion", "omissions"),
        Map.of("target", text(1, 512), "intent", text(1, 64), "items", array(item, 0, 256), "estimatedTokens", integer(0, 32768), "estimatorVersion", text(1, 64), "omissions", array(omission, 0, 256)));
  }

  private static Map<String, Object> knowledgeItem() {
    return object(
        List.of("objectType", "title", "scope", "source", "freshness", "provenance", "evidence"),
        Map.ofEntries(
            entry("objectId", text(1, 256)),
            entry("objectType", text(1, 128)),
            entry("title", text(1, 1024)),
            entry("excerpt", text(0, 16384)),
            entry("scope", enumText(List.of("SHARED", "VIRTUAL_PROJECT"))),
            entry("virtualProjectId", text(1, 256)),
            entry("source", source()),
            entry("freshness", text(1, 64)),
            entry("provenance", array(text(1, 128), 1, 32)),
            entry("evidence", array(evidence(), 0, 128))));
  }

  private static Map<String, Object> traceObject() {
    return object(List.of("objectId", "objectType", "title", "source", "freshness"), Map.of("objectId", text(1, 256), "objectType", text(1, 128), "title", text(1, 1024), "source", source(), "freshness", text(1, 64)));
  }

  private static Map<String, Object> source() {
    return object(List.of("sourceId", "relativePath"), Map.of("sourceId", text(1, 256), "relativePath", text(1, 1024), "startLine", integer(1, Integer.MAX_VALUE), "endLine", integer(1, Integer.MAX_VALUE)));
  }

  private static Map<String, Object> evidence() {
    return object(List.of("type", "sourceId"), Map.of("type", text(1, 128), "sourceId", text(1, 256), "relativePath", text(1, 1024), "startLine", integer(1, Integer.MAX_VALUE), "endLine", integer(1, Integer.MAX_VALUE), "excerpt", text(0, 4096)));
  }

  private static Map<String, Object> diagnostic() {
    return object(List.of("code", "detail"), Map.of("code", text(1, 128), "detail", text(0, 4096)));
  }

  private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
    return Map.of("type", "object", "additionalProperties", false, "required", List.copyOf(required), "properties", Map.copyOf(properties));
  }

  private static Map<String, Object> text(int minimum, int maximum) {
    return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
  }

  private static Map<String, Object> enumText(List<String> values) {
    return Map.of("type", "string", "enum", List.copyOf(values));
  }

  private static Map<String, Object> integer(int minimum, int maximum) {
    return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
  }

  private static Map<String, Object> bool() {
    return Map.of("type", "boolean");
  }

  private static Map<String, Object> array(Map<String, Object> items, int minimum, int maximum) {
    return Map.of("type", "array", "items", items, "minItems", minimum, "maxItems", maximum);
  }

  private static List<String> objectTypes() {
    return List.of(
        "DOCUMENT",
        "SECTION",
        "CHUNK",
        "REQUIREMENT",
        "DESIGN",
        "CODE_SYMBOL",
        "TEST",
        "COMMIT",
        "CONFIGURATION",
        "OTHER");
  }
}
