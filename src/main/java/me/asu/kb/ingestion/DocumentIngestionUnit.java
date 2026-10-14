package me.asu.kb.ingestion;

import java.util.Objects;
import me.asu.kb.adapter.document.LegacyDocumentSnapshot;

/** One immutable legacy-document snapshot to adapt and persist atomically. */
public record DocumentIngestionUnit(LegacyDocumentSnapshot snapshot)
    implements IngestionSourceUnit {

  public DocumentIngestionUnit {
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  @Override
  public String stableSourceId() {
    return snapshot.stableSourceId();
  }

  @Override
  public IngestionSourceCategory category() {
    return IngestionSourceCategory.DOCUMENT;
  }
}
