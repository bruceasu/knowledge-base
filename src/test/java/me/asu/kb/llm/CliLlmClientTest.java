package me.asu.kb.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CliLlmClientTest {

  @Test
  void executesEchoCommand() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    CliLlmClient client;
    if (os.contains("win")) {
      client = new CliLlmClient("cmd.exe", "/c", "echo", "{prompt}");
    } else {
      client = new CliLlmClient("echo", "{prompt}");
    }

    String result = client.generate("hello world");
    assertEquals("hello world", result.trim().replaceAll("^\"|\"$", ""));
  }

  @Test
  void isAvailableReturnsTrueForCommonCommands() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      assertTrue(CliLlmClient.isAvailable("cmd.exe"));
    } else {
      assertTrue(CliLlmClient.isAvailable("sh") || CliLlmClient.isAvailable("ls"));
    }
  }
}
