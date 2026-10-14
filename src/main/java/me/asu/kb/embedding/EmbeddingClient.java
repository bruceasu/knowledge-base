package me.asu.kb.embedding;

public interface EmbeddingClient {

  EmbeddingVector embed(String text);

  default boolean enabled() {
    return true;
  }

  static EmbeddingClient disabled() {
    return new EmbeddingClient() {
      @Override
      public EmbeddingVector embed(String text) {
        throw new UnsupportedOperationException("embedding is disabled");
      }

      @Override
      public boolean enabled() {
        return false;
      }
    };
  }
}
