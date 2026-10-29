package me.asu.kb.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/** Validated configuration only; loading settings does not open secrets or mutable state. */
public record PersonalKnowledgeSettings(
    boolean enabled, String bindHost, URI publicBaseUrl, Path credentialsFile,
    Path proxySecretFile, long storageQuotaBytes) {

  public static PersonalKnowledgeSettings localOnly() {
    return new PersonalKnowledgeSettings(false, "127.0.0.1", null, null, null, 0);
  }

  public static PersonalKnowledgeSettings parse(Map<String, String> values) {
    String enabledText = values.getOrDefault("personal_knowledge_enabled", "false");
    if (!enabledText.equals("true") && !enabledText.equals("false")) {
      throw new IllegalArgumentException("personal_knowledge_enabled must be true or false");
    }
    boolean enabled = Boolean.parseBoolean(enabledText);
    String host = values.getOrDefault("http_bind_host", "127.0.0.1");
    // This deployment profile deliberately supports only a same-host trusted proxy.
    if (!host.equals("127.0.0.1") && !host.equals("::1")) {
      throw new IllegalArgumentException("http_bind_host must be a numeric loopback address");
    }
    long quota;
    try {
      quota = Long.parseLong(values.getOrDefault("personal_storage_quota_bytes", "0"));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("personal_storage_quota_bytes must be a nonnegative integer");
    }
    if (quota < 0) throw new IllegalArgumentException("personal_storage_quota_bytes must not be negative");
    if (!enabled) return new PersonalKnowledgeSettings(false, host, null, null, null, quota);
    URI origin;
    try {
      origin = URI.create(required(values, "public_base_url"));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("public_base_url must be an HTTPS origin");
    }
    String originHost = origin.getHost();
    if (!"https".equals(origin.getScheme()) || originHost == null
        || origin.getRawUserInfo() != null || origin.getRawQuery() != null
        || origin.getRawFragment() != null || !origin.getRawPath().isEmpty()
        || origin.getPort() == 0 || origin.getPort() > 65535
        || originHost.equalsIgnoreCase("localhost") || originHost.equals("127.0.0.1")
        || originHost.equals("[::1]") || originHost.equals("::1")) {
      throw new IllegalArgumentException("public_base_url must be a browser-reachable HTTPS origin without a path");
    }
    return new PersonalKnowledgeSettings(true, host, origin,
        absoluteFile(values, "secretary_credentials_file"),
        absoluteFile(values, "trusted_proxy_secret_file"), quota);
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static Path absoluteFile(Map<String, String> values, String key) {
    Path path = Path.of(required(values, key));
    if (!path.isAbsolute()) throw new IllegalArgumentException(key + " must be an absolute path");
    return path.normalize();
  }
}
