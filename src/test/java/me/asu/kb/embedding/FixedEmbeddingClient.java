package me.asu.kb.embedding;

public final class FixedEmbeddingClient implements EmbeddingClient {

  private final String model;
  private final float[] values;

  public FixedEmbeddingClient(String model, float[] values) {
    this.model = model;
    this.values = values == null ? new float[0] : values.clone();
  }

  @Override
  public EmbeddingVector embed(String text) {
    return new EmbeddingVector(model, values);
  }
}
