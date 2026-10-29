package me.asu.kb.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import me.asu.json.OKJSON;
import me.asu.kb.runtime.PersonalKnowledgeApplication.Failure;

final class PersonalValues {
  private PersonalValues() {}
  static Failure invalid() { return new Failure(400, "INVALID_ARGUMENT"); }
  static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> source)) throw invalid();
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, item) -> { if (!(key instanceof String)) throw invalid(); result.put((String) key, item); });
    return result;
  }
  static void fields(Map<String, Object> value, String... allowed) {
    if (!Set.of(allowed).containsAll(value.keySet())) throw invalid();
  }
  static String text(Map<String, Object> value, String key, int min, int max) {
    Object raw = value.get(key);
    if (!(raw instanceof String text) || text.codePointCount(0, text.length()) < min
        || text.codePointCount(0, text.length()) > max) throw invalid();
    return text;
  }
  static int integer(Map<String, Object> value, String key, int fallback, int min, int max) {
    if (!value.containsKey(key)) return fallback;
    Object raw = value.get(key);
    if (!(raw instanceof Number number)) throw invalid();
    double n = number.doubleValue();
    if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw invalid();
    return (int) n;
  }
  static List<String> strings(Map<String, Object> values, String key, int maxCount, int maxChars) {
    if (!values.containsKey(key)) return List.of();
    if (!(values.get(key) instanceof List<?> list) || list.size() > maxCount) throw invalid();
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String)) throw invalid();
      result.add(text(Map.of("v", item), "v", 1, maxChars));
    }
    return List.copyOf(result);
  }
  static String json(Object value) {
    return OKJSON.stringify(value, OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE | OKJSON.OPTIONS_NULLABLE);
  }
  static Map<String, Object> parse(String json) {
    try { return map(OKJSON.toJson(json, Map.class, OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE)); }
    catch (RuntimeException invalid) { throw invalid(); }
  }
  static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(PersonalValues::canonical).toList();
    return value;
  }
  static String hash(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static String slice(String value, int offset, int length) {
    int count = value.codePointCount(0, value.length());
    if (offset < 0 || offset > count) throw invalid();
    return value.substring(value.offsetByCodePoints(0, offset), value.offsetByCodePoints(0, Math.min(count, offset + length)));
  }
  static Map<String, Object> location() {
    var location = new LinkedHashMap<String, Object>();
    location.put("kind", "document"); location.put("label", "正文");
    location.put("page", null); location.put("startLine", null); location.put("endLine", null);
    return location;
  }
}
