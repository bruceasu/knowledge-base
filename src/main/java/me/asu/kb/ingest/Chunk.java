package me.asu.kb.ingest;

public record Chunk(
    int chunkNo,
    String title,
    String content,
    int startLine,
    int endLine) {}
