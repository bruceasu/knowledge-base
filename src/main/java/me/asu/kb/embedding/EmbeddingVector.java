package me.asu.kb.embedding;

public record EmbeddingVector(String model, float[] values) {

  public EmbeddingVector {
    values = values == null ? new float[0] : values.clone();
  }
}
