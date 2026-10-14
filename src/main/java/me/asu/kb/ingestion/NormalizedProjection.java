package me.asu.kb.ingestion;

import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;

/** Complete persistence candidate for one source unit, staged outside a Store transaction. */
record NormalizedProjection(
    ProjectionWrite write,
    int objectCount,
    int evidenceCount,
    String inputHash,
    String profileId,
    String profileVersion) {}
