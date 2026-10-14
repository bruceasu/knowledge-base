package me.asu.kb.context;

import java.util.Objects;

/** Deduplicated candidate with aggregated provenance and a fixed selection reason. */
record SelectedContextCandidate(
    ContextCandidate candidate,
    ContextSelectionReason selectionReason,
    ContextProvenance provenance) {

  SelectedContextCandidate {
    candidate = Objects.requireNonNull(candidate, "candidate");
    selectionReason = Objects.requireNonNull(selectionReason, "selectionReason");
    provenance = Objects.requireNonNull(provenance, "provenance");
  }
}
