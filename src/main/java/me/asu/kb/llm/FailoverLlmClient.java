package me.asu.kb.llm;

import java.util.List;
import java.util.stream.Collectors;
import me.asu.log.Log;

public class FailoverLlmClient implements LlmClient {

  private static class ClientState {
    final LlmClient client;
    final String description;
    long lastFailureTime = 0;

    ClientState(LlmClient client) {
      this.client = client;
      this.description = client.toString();
    }
  }

  private final List<ClientState> clientStates;
  private final long cooldownMillis;

  public FailoverLlmClient(List<LlmClient> clients) {
    this(clients, 60000);
  }

  public FailoverLlmClient(List<LlmClient> clients, long cooldownMillis) {
    if (clients == null || clients.isEmpty()) {
      throw new IllegalArgumentException("Clients list cannot be null or empty");
    }
    this.clientStates = clients.stream().map(ClientState::new).collect(Collectors.toList());
    this.cooldownMillis = cooldownMillis;
  }

  @Override
  public String generate(String prompt) {
    Throwable lastError = null;
    long now = System.currentTimeMillis();

    for (ClientState state : clientStates) {
      // Check if client is in cooldown
      if (state.lastFailureTime > 0 && (now - state.lastFailureTime) < cooldownMillis) {
        continue;
      }

      try {
        String result = state.client.generate(prompt);
        // Success: clear failure state if it was cooling down
        state.lastFailureTime = 0;
        return result;
      } catch (Exception e) {
        state.lastFailureTime = System.currentTimeMillis();
        Log.error("LLM client " + state.description + " failed: " + e.getMessage() + ". Entering cooldown.");
        lastError = e;
      }
    }
    
    // If all usable clients failed or are in cooldown, try the ones in cooldown as a last resort
    // only if we haven't found a successful one yet.
    if (lastError != null) {
        throw new RuntimeException("All LLM clients failed (including those in cooldown)", lastError);
    } else {
        throw new RuntimeException("All LLM clients are currently in cooldown");
    }
  }
}
