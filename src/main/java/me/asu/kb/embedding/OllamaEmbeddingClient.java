package me.asu.kb.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OllamaEmbeddingClient implements EmbeddingClient {

  private final HttpClient httpClient;
  private final String endpoint;
  private final String model;

  public OllamaEmbeddingClient(String endpoint, String model) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.endpoint = endpoint;
    this.model = model;
  }

  @Override
  public EmbeddingVector embed(String text) {
    try {
      String body =
          "{"
              + "\"model\":\""
              + escape(model)
              + "\","
              + "\"prompt\":\""
              + escape(text)
              + "\""
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
        throw new IOException(
            "Ollama embedding request failed: "
                + response.statusCode()
                + " "
                + response.body());
      }
      return new EmbeddingVector(model, parseEmbedding(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private float[] parseEmbedding(String json) {
    String token = "\"embedding\":";
    int index = json.indexOf(token);
    if (index < 0) {
      return new float[0];
    }
    int start = json.indexOf('[', index);
    int end = json.indexOf(']', start + 1);
    if (start < 0 || end < 0) {
      return new float[0];
    }
    String body = json.substring(start + 1, end).trim();
    if (body.isEmpty()) {
      return new float[0];
    }
    String[] parts = body.split(",");
    float[] values = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = Float.parseFloat(parts[i].trim());
    }
    return values;
  }

  private String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
