package me.asu.kb.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.adapter.codesearch.RipgrepCodeSearchAdapter;
import me.asu.kb.adapter.gitevidence.GitEvidenceSettings;
import me.asu.kb.adapter.symbol.CtagsSymbolExtractor;
import me.asu.kb.llm.LlmClient;
import me.asu.kb.llm.LlmClientFactory;
import me.asu.kb.llm.LlmProviderSettings;
import me.asu.kb.llm.ModelConfig;
import me.asu.kb.service.KnowledgeBase;

/** Shared configuration and production composition entry point for the HTTP launcher. */
public final class SingleWorkspaceLauncher {
  private static final String CONFIG = "--config";

  private SingleWorkspaceLauncher() {}

  public static Configuration load(String[] arguments) throws IOException {
    String[] args = arguments == null ? new String[0] : arguments.clone();
    Path configFile = configFile(args);
    Map<String, Object> toml = configFile == null ? new LinkedHashMap<>() : readToml(configFile);
    if (hasCanonicalCliRoot(args)) {
      toml.remove(SingleWorkspaceServiceConfig.TOML_WORKSPACE_ROOT);
    }
    SingleWorkspaceServiceConfig service = SingleWorkspaceServiceConfig.parse(args, toml);
    Map<String, String> values = stringValues(toml);
    overlayCli(values, args);
    LlmProviderSettings llm = llmConfiguration(values);
    SingleWorkspaceKnowledgeService.ToolProfiles tools = toolProfiles(values);
    return new Configuration(
        service,
        llm,
        tools,
        httpPort(values),
        virtualProjectSyncInterval(values),
        virtualProjectSyncConcurrency(values),
        PersonalKnowledgeSettings.parse(values));
  }

  public static SingleWorkspaceKnowledgeService open(Configuration configuration) throws Exception {
    Objects.requireNonNull(configuration, "configuration");
    LlmClient llm = new LlmClientFactory().create(configuration.llm());
    return SingleWorkspaceKnowledgeService.open(
        configuration.service(),
        documentPath -> openDocuments(configuration, llm, documentPath),
        configuration.tools(),
        configuration.virtualProjectSyncInterval(),
        configuration.virtualProjectSyncConcurrency());
  }

  private static SingleWorkspaceDocumentApplication openDocuments(
      Configuration configuration, LlmClient llm, Path documentPath) throws Exception {
    KnowledgeBase knowledgeBase = new KnowledgeBase(documentPath, llm);
    try {
      if (configuration.personalKnowledge().enabled()) knowledgeBase.initializePersonalService(configuration.personalKnowledge());
      return new KnowledgeBaseDocumentApplication(configuration.service().workspaceRoot(), knowledgeBase);
    } catch (Exception failure) {
      try { knowledgeBase.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
      throw failure;
    }
  }

  private static Path configFile(String[] args) {
    Path selected = null;
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (CONFIG.equals(argument)) {
        if (index + 1 >= args.length || args[index + 1] == null || args[index + 1].startsWith("--")) {
          throw new IllegalArgumentException("missing value for --config");
        }
        if (selected != null) throw new IllegalArgumentException("--config may be supplied only once");
        selected = Path.of(args[++index]).toAbsolutePath().normalize();
      } else if (argument != null && argument.startsWith(CONFIG + "=")) {
        if (selected != null) throw new IllegalArgumentException("--config may be supplied only once");
        selected = Path.of(argument.substring((CONFIG + "=").length())).toAbsolutePath().normalize();
      }
    }
    if (selected != null && !Files.isRegularFile(selected)) {
      throw new IllegalArgumentException("configuration file does not exist: " + selected);
    }
    return selected;
  }

  private static boolean hasCanonicalCliRoot(String[] args) {
    for (String argument : args) {
      if (SingleWorkspaceServiceConfig.CLI_WORKSPACE_ROOT.equals(argument)
          || (argument != null
              && argument.startsWith(SingleWorkspaceServiceConfig.CLI_WORKSPACE_ROOT + "="))) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> readToml(Path file) throws IOException {
    Map<String, Object> values = new LinkedHashMap<>();
    Path base = file.getParent();
    int lineNumber = 0;
    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      lineNumber++;
      String line = stripComment(raw).trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        throw new IllegalArgumentException("TOML sections are not supported by the service launcher at line " + lineNumber);
      }
      int separator = line.indexOf('=');
      if (separator <= 0) throw new IllegalArgumentException("invalid TOML assignment at line " + lineNumber);
      String key = line.substring(0, separator).trim();
      String value = expandEnvironment(unquote(line.substring(separator + 1).trim()));
      if (values.putIfAbsent(key, value) != null) {
        throw new IllegalArgumentException("duplicate TOML key: " + key);
      }
    }
    Object root = values.get(SingleWorkspaceServiceConfig.TOML_WORKSPACE_ROOT);
    if (root instanceof String text && !text.isBlank() && base != null) {
      Path parsed = Path.of(text);
      if (!parsed.isAbsolute()) values.put(SingleWorkspaceServiceConfig.TOML_WORKSPACE_ROOT, base.resolve(parsed).normalize().toString());
    }
    return values;
  }

  private static String stripComment(String value) {
    boolean quoted = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '"') quoted = !quoted;
      else if (current == '#' && !quoted) return value.substring(0, index);
    }
    return value;
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String expandEnvironment(String value) {
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    while (cursor < value.length()) {
      int start = value.indexOf("${", cursor);
      if (start < 0) { result.append(value.substring(cursor)); break; }
      result.append(value, cursor, start);
      int end = value.indexOf('}', start + 2);
      if (end < 0) throw new IllegalArgumentException("unterminated environment reference");
      String name = value.substring(start + 2, end);
      String replacement = System.getenv(name);
      if (replacement == null) throw new IllegalArgumentException("environment variable is not defined: " + name);
      result.append(replacement);
      cursor = end + 1;
    }
    return result.toString();
  }

  private static Map<String, String> stringValues(Map<String, Object> source) {
    Map<String, String> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
    return result;
  }

  private static void overlayCli(Map<String, String> values, String[] args) {
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument == null || !argument.startsWith("--")) continue;
      String key;
      String value;
      int equals = argument.indexOf('=');
      if (equals > 2) {
        key = argument.substring(2, equals).replace('-', '_');
        value = argument.substring(equals + 1);
      } else {
        key = argument.substring(2).replace('-', '_');
        if ("config".equals(key)) { index++; continue; }
        if (index + 1 >= args.length || args[index + 1] == null || args[index + 1].startsWith("--")) {
          throw new IllegalArgumentException("missing value for --" + key.replace('_', '-'));
        }
        value = args[++index];
      }
      values.put(key, value);
    }
  }

  private static LlmProviderSettings llmConfiguration(Map<String, String> values) {
    String provider = configured(values, "llm", configured(values, "llm_provider", "ollama"));
    return new RuntimeLlmSettings(
        provider,
        configured(values, "model", "qwen3:4b"),
        configured(values, "ollama_url", "http://localhost:11434/api/generate"),
        configured(values, "openai_url", "https://api.openai.com/v1/chat/completions"),
        values.get("openai_api_key"),
        configured(values, "openai_model", "gpt-4o-mini"),
        configured(values, "gemini_url", "https://generativelanguage.googleapis.com/v1beta"),
        values.get("gemini_api_key"),
        configured(values, "gemini_model", "gemini-2.0-flash"),
        configured(values, "groq_url", "https://api.groq.com/openai/v1/chat/completions"),
        values.get("groq_api_key"),
        configured(values, "groq_model", "llama-3.1-8b-instant"),
        List.of());
  }

  private static SingleWorkspaceKnowledgeService.ToolProfiles toolProfiles(Map<String, String> values) {
    String ripgrep = absoluteExecutable(values.get("ripgrep_executable"), "ripgrep_executable");
    String ctags = absoluteExecutable(values.get("ctags_executable"), "ctags_executable");
    String git = absoluteExecutable(values.get("git_executable"), "git_executable");
    RipgrepCodeSearchAdapter.Settings rgProfile = ripgrep == null
        ? RipgrepCodeSearchAdapter.Settings.unavailable()
        : new RipgrepCodeSearchAdapter.Settings(ripgrep, 512, 64, 3, 200, 4_096, 1_048_576, Duration.ofSeconds(5));
    return new SingleWorkspaceKnowledgeService.ToolProfiles(
        rgProfile,
        ctags == null ? CtagsSymbolExtractor.Settings.unavailable() : CtagsSymbolExtractor.Settings.defaults(ctags),
        git == null ? GitEvidenceSettings.unavailable() : GitEvidenceSettings.defaults(git));
  }

  private static int httpPort(Map<String, String> values) {
    String configured = values.get("port");
    if (configured == null || configured.isBlank()) return 8080;
    try {
      int port = Integer.parseInt(configured);
      if (port < 1 || port > 65_535) throw new IllegalArgumentException("port must be between 1 and 65535");
      return port;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("port must be an integer", error);
    }
  }

  private static Duration virtualProjectSyncInterval(Map<String, String> values) {
    String configured = values.get("virtual_project_sync_interval");
    if (configured == null || configured.isBlank()) {
      return null;
    }
    final Duration interval;
    try {
      interval = Duration.parse(configured.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "virtual_project_sync_interval must be an ISO-8601 duration", invalid);
    }
    if (interval.compareTo(VirtualProjectSyncScheduler.MIN_INTERVAL) < 0
        || interval.compareTo(VirtualProjectSyncScheduler.MAX_INTERVAL) > 0) {
      throw new IllegalArgumentException(
          "virtual_project_sync_interval must be between "
              + VirtualProjectSyncScheduler.MIN_INTERVAL
              + " and "
              + VirtualProjectSyncScheduler.MAX_INTERVAL);
    }
    return interval;
  }

  private static int virtualProjectSyncConcurrency(Map<String, String> values) {
    String configured = values.get("virtual_project_sync_concurrency");
    if (configured == null || configured.isBlank()) {
      return VirtualProjectSyncScheduler.DEFAULT_MAX_CONCURRENT_PROJECTS;
    }
    try {
      int concurrency = Integer.parseInt(configured.trim());
      if (concurrency < 1 || concurrency > 32) {
        throw new IllegalArgumentException(
            "virtual_project_sync_concurrency must be between 1 and 32");
      }
      return concurrency;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(
          "virtual_project_sync_concurrency must be an integer", invalid);
    }
  }

  private static String absoluteExecutable(String value, String key) {
    if (value == null || value.isBlank()) return null;
    try {
      if (!Path.of(value).isAbsolute()) throw new IllegalArgumentException(key + " must be an absolute path");
      return Path.of(value).normalize().toString();
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException(key + " is invalid", error);
    }
  }

  private static String configured(Map<String, String> values, String key, String fallback) {
    String value = values.get(key);
    return value == null ? fallback : value;
  }

  public record Configuration(
      SingleWorkspaceServiceConfig service,
      LlmProviderSettings llm,
      SingleWorkspaceKnowledgeService.ToolProfiles tools,
      int httpPort,
      Duration virtualProjectSyncInterval,
      int virtualProjectSyncConcurrency,
      PersonalKnowledgeSettings personalKnowledge) {
    public Configuration(
        SingleWorkspaceServiceConfig service,
        LlmProviderSettings llm,
        SingleWorkspaceKnowledgeService.ToolProfiles tools,
        int httpPort,
        Duration virtualProjectSyncInterval,
        int virtualProjectSyncConcurrency) {
      this(service, llm, tools, httpPort, virtualProjectSyncInterval,
          virtualProjectSyncConcurrency, PersonalKnowledgeSettings.localOnly());
    }
    public Configuration(
        SingleWorkspaceServiceConfig service,
        LlmProviderSettings llm,
        SingleWorkspaceKnowledgeService.ToolProfiles tools,
        int httpPort) {
      this(
          service,
          llm,
          tools,
          httpPort,
          null,
          VirtualProjectSyncScheduler.DEFAULT_MAX_CONCURRENT_PROJECTS);
    }

    public Configuration {
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(llm, "llm");
      Objects.requireNonNull(tools, "tools");
      Objects.requireNonNull(personalKnowledge, "personalKnowledge");
      if (httpPort < 1 || httpPort > 65_535) {
        throw new IllegalArgumentException("httpPort must be between 1 and 65535");
      }
      if (virtualProjectSyncConcurrency < 1 || virtualProjectSyncConcurrency > 32) {
        throw new IllegalArgumentException("virtualProjectSyncConcurrency must be between 1 and 32");
      }
      if (virtualProjectSyncInterval != null
          && (virtualProjectSyncInterval.compareTo(VirtualProjectSyncScheduler.MIN_INTERVAL) < 0
              || virtualProjectSyncInterval.compareTo(VirtualProjectSyncScheduler.MAX_INTERVAL) > 0)) {
        throw new IllegalArgumentException("virtualProjectSyncInterval is outside the supported range");
      }
    }
  }

  private record RuntimeLlmSettings(
      String provider,
      String model,
      String ollamaEndpoint,
      String openAiEndpoint,
      String openAiApiKey,
      String openAiModel,
      String geminiEndpoint,
      String geminiApiKey,
      String geminiModel,
      String groqEndpoint,
      String groqApiKey,
      String groqModel,
      List<ModelConfig> llmModels)
      implements LlmProviderSettings {
    private RuntimeLlmSettings {
      llmModels = List.copyOf(llmModels);
    }
  }
}
