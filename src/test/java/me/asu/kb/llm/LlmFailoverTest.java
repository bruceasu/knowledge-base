package me.asu.kb.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LlmFailoverTest {

  @Test
  void testFailover() {
    LlmClient failClient = prompt -> { throw new RuntimeException("fail"); };
    LlmClient successClient = prompt -> "success";
    
    FailoverLlmClient failoverClient = new FailoverLlmClient(List.of(failClient, successClient));
    assertEquals("success", failoverClient.generate("test"));
  }

  @Test
  void testAllFail() {
    LlmClient failClient1 = prompt -> { throw new RuntimeException("fail 1"); };
    LlmClient failClient2 = prompt -> { throw new RuntimeException("fail 2"); };
    
    FailoverLlmClient failoverClient = new FailoverLlmClient(List.of(failClient1, failClient2));
    assertThrows(RuntimeException.class, () -> failoverClient.generate("test"));
  }
}
