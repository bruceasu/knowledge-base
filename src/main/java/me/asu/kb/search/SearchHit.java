package me.asu.kb.search;

import java.util.List;

public record SearchHit(
    long documentId,
    long chunkId,
    String path,
    String title,
    int chunkNo,
    String snippet,
    String summary,
    float score,
    List<String> tags) {}
