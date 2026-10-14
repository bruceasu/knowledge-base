package me.asu.kb.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OllamaLlmClient implements LlmClient {

  private final HttpClient httpClient;
  private final String endpoint;
  private final String model;

  public OllamaLlmClient(String endpoint, String model) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.endpoint = endpoint;
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
              + "\"prompt\":\""
              + escape(prompt)
              + "\","
              + "\"stream\":false"
              + "}";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        throw new IOException("Ollama request failed: " + response.statusCode() + " " + response.body());
      }
      return extractField(response.body(), "response");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String extractField(String json, String field) {
    String token = "\"" + field + "\":";
    int index = json.indexOf(token);
    if (index < 0) {
      return json;
    }
    int start = json.indexOf('"', index + token.length());
    if (start < 0) {
      return json;
    }
    StringBuilder value = new StringBuilder();
    boolean escaped = false;
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaped) {
        value.append(c);
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
