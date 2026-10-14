package me.asu.kb.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GeminiLlmClientTest {

  @Test
  void sendsGenerateContentRequestAndReadsAnswer() throws Exception {
    AtomicReference<String> bodyRef = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/v1beta/models/gemini-2.0-flash:generateContent",
          exchange -> {
            bodyRef.set(readBody(exchange));
            respond(exchange, 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini answer\"}]}}]}");
          });
      server.start();

      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
      GeminiLlmClient client = new GeminiLlmClient(endpoint, "gem-key", "gemini-2.0-flash");
      String answer = client.generate("Hello world");

      assertEquals("Gemini answer", answer);
      assertTrue(bodyRef.get().contains("\"contents\""));
      assertTrue(bodyRef.get().contains("Hello world"));
    } finally {
      server.stop(0);
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
