package me.asu.kb.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenAiLlmClient implements LlmClient {

  private final HttpClient httpClient;
  private final String endpoint;
  private final String apiKey;
  private final String model;

  public OpenAiLlmClient(String endpoint, String apiKey, String model) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.model = model;
  }

  @Override
  public String generate(String prompt) {
    try {
      String body =
          "{"
              + "\"model\":\""
              + escape(model)
              + "\","
              + "\"messages\":[{\"role\":\"user\",\"content\":\""
              + escape(prompt)
              + "\"}]"
              + "}";
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json");
      if (apiKey != null && !apiKey.isBlank()) {
        builder.header("Authorization", "Bearer " + apiKey);
      }
      HttpResponse<String> response =
          httpClient.send(
              builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      ensureSuccess(response);
      return extractMessage(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void ensureSuccess(HttpResponse<String> response) throws IOException {
    if (response.statusCode() >= 400) {
      throw new IOException("OpenAI request failed: " + response.statusCode() + " " + response.body());
    }
  }

  private String extractMessage(String json) {
    String token = "\"content\":";
    int index = json.indexOf(token);
    if (index < 0) {
      return json;
    }
    int start = json.indexOf('"', index + token.length());
    if (start < 0) {
      return json;
    }
    return extractQuoted(json, start);
  }

  private String extractQuoted(String json, int startQuoteIndex) {
    StringBuilder value = new StringBuilder();
    boolean escaped = false;
    for (int i = startQuoteIndex + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaped) {
        switch (c) {
          case 'n' -> value.append('\n');
          case 'r' -> value.append('\r');
          case 't' -> value.append('\t');
          case '"' -> value.append('"');
          case '\\' -> value.append('\\');
          default -> value.append(c);
        }
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        return value.toString();
      } else {
        value.append(c);
      }
    }
    return json;
  }

  private String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
