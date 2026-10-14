package me.asu.kb.llm;

public final class EchoLlmClient implements LlmClient {

  @Override
  public String generate(String prompt) {
    return "ANSWER:\n" + prompt;
  }
}
