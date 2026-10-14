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

class GroqLlmClientTest {

  @Test
  void sendsOpenAiCompatibleRequestAndReadsAnswer() throws Exception {
    AtomicReference<String> bodyRef = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/openai/v1/chat/completions",
          exchange -> {
            bodyRef.set(readBody(exchange));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Groq answer\"}}]}");
          });
      server.start();

      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/chat/completions";
      GroqLlmClient client = new GroqLlmClient(endpoint, "gq-test", "llama-3.1-8b-instant");
      String answer = client.generate("Hello world");

      assertEquals("Groq answer", answer);
      assertTrue(bodyRef.get().contains("\"model\":\"llama-3.1-8b-instant\""));
      assertTrue(bodyRef.get().contains("\"messages\""));
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
