package me.asu.kb.ingestion;

/** Immutable source unit whose scope, snapshot, profile, and resource estimates are verified. */
record ValidatedIngestionUnit(
    IngestionSourceUnit sourceUnit,
    String inputHash,
    String profileId,
    String profileVersion,
    int estimatedObjectCount,
    int provenanceEntryCount) {}
