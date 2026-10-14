package me.asu.kb.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LlmClientFactory {

  static final String DEFAULT_PROVIDER = "ollama";
  static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434/api/generate";
  static final String DEFAULT_OLLAMA_MODEL = "qwen3:4b";
  static final String DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
  static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
  static final String DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta";
  static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";
  static final String DEFAULT_GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
  static final String DEFAULT_GROQ_MODEL = "llama-3.1-8b-instant";

  public LlmClient create(LlmProviderSettings settings) {
    List<ModelConfig> models = settings == null ? null : settings.llmModels();
    if (models != null && !models.isEmpty()) {
      List<LlmClient> clients = new ArrayList<>();
      for (ModelConfig modelConfig : models) {
        clients.add(create(modelConfig, settings));
      }
      return new FailoverLlmClient(clients, settings.llmCooldown());
    }

    String provider = normalize(settings == null ? null : settings.provider());
    return create(provider, settings);
  }

  public LlmClient create(ModelConfig config, LlmProviderSettings settings) {
    String provider = normalize(config.getProvider());
    return switch (provider) {
      case "echo" -> new EchoLlmClient();
      case "ollama" -> new OllamaLlmClient(
          valueOrDefault(config.getEndpoint(),
              valueOrDefault(settings == null ? null : settings.ollamaEndpoint(),
                  DEFAULT_OLLAMA_ENDPOINT)),
          valueOrDefault(config.getModel(),
              valueOrDefault(settings == null ? null : settings.model(), DEFAULT_OLLAMA_MODEL)));
      case "openai" -> new OpenAiLlmClient(
          valueOrDefault(config.getEndpoint(),
              valueOrDefault(settings == null ? null : settings.openAiEndpoint(),
                  DEFAULT_OPENAI_ENDPOINT)),
          valueOrDefault(config.getApiKey(),
              valueOrDefault(settings == null ? null : settings.openAiApiKey(), null)),
          valueOrDefault(config.getModel(),
              valueOrDefault(settings == null ? null : settings.openAiModel(),
                  valueOrDefault(settings == null ? null : settings.model(),
                      DEFAULT_OPENAI_MODEL))));
      case "gemini" -> new GeminiLlmClient(
          valueOrDefault(config.getEndpoint(),
              valueOrDefault(settings == null ? null : settings.geminiEndpoint(),
                  DEFAULT_GEMINI_ENDPOINT)),
          valueOrDefault(config.getApiKey(),
              valueOrDefault(settings == null ? null : settings.geminiApiKey(), null)),
          valueOrDefault(config.getModel(),
              valueOrDefault(settings == null ? null : settings.geminiModel(),
                  valueOrDefault(settings == null ? null : settings.model(),
                      DEFAULT_GEMINI_MODEL))));
      case "groq" -> new GroqLlmClient(
          valueOrDefault(config.getEndpoint(),
              valueOrDefault(settings == null ? null : settings.groqEndpoint(),
                  DEFAULT_GROQ_ENDPOINT)),
          valueOrDefault(config.getApiKey(),
              valueOrDefault(settings == null ? null : settings.groqApiKey(), null)),
          valueOrDefault(config.getModel(),
              valueOrDefault(settings == null ? null : settings.groqModel(),
                  valueOrDefault(settings == null ? null : settings.model(),
                      DEFAULT_GROQ_MODEL))));
      case "copilot" -> {
        if (CliLlmClient.isAvailable("copilot")) {
          yield new CliLlmClient( "copilot", "-p", "{prompt}");
        } else if (CliLlmClient.isAvailable("gh")) {
          yield new CliLlmClient("gh", "copilot", "explain", "{prompt}");
        } else {
          throw new IllegalArgumentException("GitHub CLI (gh) with copilot extension is not available in PATH.");
        }
      }
      case "codex" -> {
        if (CliLlmClient.isAvailable("codex")) {
          yield new CliLlmClient("codex", "{prompt}");
        } else {
          throw new IllegalArgumentException("Codex CLI is not available in PATH.");
        }
      }
      default -> throw new IllegalArgumentException("Unknown LLM provider: " + provider);
    };
  }

  private LlmClient create(String provider, LlmProviderSettings settings) {
    return switch (provider) {
      case "echo" -> new EchoLlmClient();
      case "ollama" -> new OllamaLlmClient(
          valueOrDefault(settings == null ? null : settings.ollamaEndpoint(), DEFAULT_OLLAMA_ENDPOINT),
          valueOrDefault(settings == null ? null : settings.model(), DEFAULT_OLLAMA_MODEL));
      case "openai" -> new OpenAiLlmClient(
          valueOrDefault(settings == null ? null : settings.openAiEndpoint(), DEFAULT_OPENAI_ENDPOINT),
          valueOrDefault(settings == null ? null : settings.openAiApiKey(), null),
          valueOrDefault(
              settings == null ? null : settings.openAiModel(),
              valueOrDefault(settings == null ? null : settings.model(), DEFAULT_OPENAI_MODEL)));
      case "gemini" -> new GeminiLlmClient(
          valueOrDefault(settings == null ? null : settings.geminiEndpoint(), DEFAULT_GEMINI_ENDPOINT),
          valueOrDefault(settings == null ? null : settings.geminiApiKey(), null),
          valueOrDefault(
              settings == null ? null : settings.geminiModel(),
              valueOrDefault(settings == null ? null : settings.model(), DEFAULT_GEMINI_MODEL)));
      case "groq" -> new GroqLlmClient(
          valueOrDefault(settings == null ? null : settings.groqEndpoint(), DEFAULT_GROQ_ENDPOINT),
          valueOrDefault(settings == null ? null : settings.groqApiKey(), null),
          valueOrDefault(
              settings == null ? null : settings.groqModel(),
              valueOrDefault(settings == null ? null : settings.model(), DEFAULT_GROQ_MODEL)));
      case "copilot" -> {
        if (CliLlmClient.isAvailable("copilot")) {
          yield new CliLlmClient( "copilot", "-sp", "{prompt}");
        } else if (CliLlmClient.isAvailable("gh")) {
          yield new CliLlmClient("gh", "copilot", "explain", "{prompt}");
        } else {
          throw new IllegalArgumentException("GitHub CLI (gh) with copilot extension is not available in PATH.");
        }
      }
      case "codex" -> {
        if (CliLlmClient.isAvailable("codex")) {
          yield new CliLlmClient("codex", "exec", "{prompt}");
        } else {
          throw new IllegalArgumentException("Codex CLI is not available in PATH.");
        }
      }
      default -> throw new IllegalArgumentException("Unknown LLM provider: " + provider);
    };
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_PROVIDER;
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
