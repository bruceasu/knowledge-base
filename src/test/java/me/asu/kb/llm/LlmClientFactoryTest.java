package me.asu.kb.llm;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {

  @Test
  void createsEchoClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new TestSettings(
                    "echo",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertInstanceOf(EchoLlmClient.class, client);
  }

  @Test
  void createsOllamaClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new TestSettings(
                    "ollama",
                    null,
                    "http://localhost:11434/api/generate",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertInstanceOf(OllamaLlmClient.class, client);
  }

  @Test
  void createsOpenAiClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new TestSettings(
                    "openai",
                    "gpt-4o-mini",
                    null,
                    "https://example.com/v1/chat/completions",
                    "sk-test",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertInstanceOf(OpenAiLlmClient.class, client);
  }

  @Test
  void createsGeminiClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new TestSettings(
                    "gemini",
                    "gemini-2.0-flash",
                    null,
                    null,
                    null,
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gem-key",
                    null,
                    null,
                    null,
                    null));

    assertInstanceOf(GeminiLlmClient.class, client);
  }

  @Test
  void createsGroqClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new TestSettings(
                    "groq",
                    "llama-3.1-8b-instant",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "https://example.com/openai/v1/chat/completions",
                    "gq-test",
                    null,
                    null));

    assertInstanceOf(GroqLlmClient.class, client);
  }

  @Test
  void rejectsUnknownProvider() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LlmClientFactory()
                .create(
                    new TestSettings(
                        "unknown",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
  }

  private record TestSettings(
      String provider,
      String model,
      String ollamaEndpoint,
      String openAiEndpoint,
      String openAiApiKey,
      String geminiEndpoint,
      String geminiApiKey,
      String groqEndpoint,
      String groqApiKey,
      String openAiModel,
      String geminiModel)
      implements LlmProviderSettings {

    @Override
    public String provider() {
      return provider;
    }

    @Override
    public String model() {
      return model;
    }

    @Override
    public String ollamaEndpoint() {
      return ollamaEndpoint;
    }

    @Override
    public String openAiEndpoint() {
      return openAiEndpoint;
    }

    @Override
    public String openAiApiKey() {
      return openAiApiKey;
    }

    @Override
    public String geminiEndpoint() {
      return geminiEndpoint;
    }

    @Override
    public String geminiApiKey() {
      return geminiApiKey;
    }

    @Override
    public String groqEndpoint() {
      return groqEndpoint;
    }

    @Override
    public String groqApiKey() {
      return groqApiKey;
    }

    @Override
    public String openAiModel() {
      return openAiModel;
    }

    @Override
    public String geminiModel() {
      return geminiModel;
    }

    @Override
    public String groqModel() {
      return null;
    }

    @Override
    public java.util.List<ModelConfig> llmModels() {
      return null;
    }
  }

  @Test
  void createsFailoverClient() {
    LlmClient client =
        new LlmClientFactory()
            .create(
                new LlmProviderSettings() {
                  @Override public String provider() { return "echo"; }
                  @Override public String model() { return null; }
                  @Override public String ollamaEndpoint() { return null; }
                  @Override public String openAiEndpoint() { return null; }
                  @Override public String openAiApiKey() { return null; }
                  @Override public String openAiModel() { return null; }
                  @Override public String geminiEndpoint() { return null; }
                  @Override public String geminiApiKey() { return null; }
                  @Override public String geminiModel() { return null; }
                  @Override public String groqEndpoint() { return null; }
                  @Override public String groqApiKey() { return null; }
                  @Override public String groqModel() { return null; }
                  @Override public java.util.List<ModelConfig> llmModels() {
                    return java.util.List.of(new ModelConfig("echo", "model1"), new ModelConfig("echo", "model2"));
                  }
                });

    assertInstanceOf(FailoverLlmClient.class, client);
  }
}
