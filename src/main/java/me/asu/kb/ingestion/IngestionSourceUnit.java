package me.asu.kb.ingestion;

/** One immutable source unit and the atomic reporting/persistence identity for ingestion. */
public sealed interface IngestionSourceUnit
    permits DocumentIngestionUnit,
        JavaSymbolIngestionUnit,
        GitCommitIngestionUnit,
        UnsupportedIngestionUnit {

  String stableSourceId();

  IngestionSourceCategory category();
}
