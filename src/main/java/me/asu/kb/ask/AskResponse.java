package me.asu.kb.ask;

import java.util.List;

public record AskResponse(
    Long sessionId,
    String answer,
    List<SourceReference> sources,
    String prompt) {}
