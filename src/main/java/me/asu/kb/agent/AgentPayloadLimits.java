package me.asu.kb.agent;

/** Shared UTF-8 payload limits applied before facade calls and before transport writes. */
public final class AgentPayloadLimits {
  public static final int MAXIMUM_REQUEST_BYTES = 65_536;
  public static final int MAXIMUM_RESPONSE_BYTES = 1_048_576;

  private AgentPayloadLimits() {}

  public static void ensureRequestSize(int encodedBytes) throws AgentRequestException {
    if (encodedBytes < 0 || encodedBytes > MAXIMUM_REQUEST_BYTES) {
      throw new AgentRequestException(
          AgentToolValues.Status.INVALID_ARGUMENT,
          "REQUEST_SIZE_EXCEEDED",
          "request exceeds the 65536-byte limit");
    }
  }

  public static void ensureCompleteResponseSize(int encodedBytes) throws AgentRequestException {
    if (encodedBytes < 0 || encodedBytes > MAXIMUM_RESPONSE_BYTES) {
      throw new AgentRequestException(
          AgentToolValues.Status.INTERNAL,
          "RESPONSE_SIZE_EXCEEDED",
          "tool response exceeds the configured limit");
    }
  }
}
