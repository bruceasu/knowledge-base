package me.asu.kb.llm;

import java.util.List;

public interface LlmProviderSettings {

  String provider();

  String model();

  String ollamaEndpoint();

  String openAiEndpoint();

  String openAiApiKey();

  String openAiModel();

  String geminiEndpoint();

  String geminiApiKey();

  String geminiModel();

  String groqEndpoint();

  String groqApiKey();

  String groqModel();

  List<ModelConfig> llmModels();

  default long llmCooldown() {
    return 60000;
  }
}
