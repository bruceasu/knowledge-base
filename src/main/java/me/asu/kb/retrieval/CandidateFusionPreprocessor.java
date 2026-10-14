package me.asu.kb.retrieval;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;

/** Validates confinement and merges only candidates with proven identity or precise location. */
final class CandidateFusionPreprocessor {
  CandidatePreparation prepare(
      ValidatedKnowledgeQuery query,
      List<CandidateBatch> batches,
      GitEvidenceEnrichment gitEnrichment) {
    List<RetrievalDiagnostic> diagnostics = new ArrayList<>();
    batches.forEach(batch -> diagnostics.addAll(batch.diagnostics()));
    diagnostics.addAll(gitEnrichment.diagnostics());

    List<Group> groups = new ArrayList<>();
    Map<String, Group> byObjectId = new LinkedHashMap<>();
    Map<String, Group> byLocation = new LinkedHashMap<>();
    int accepted = 0;
    boolean truncated = gitEnrichment.truncated() || batches.stream().anyMatch(CandidateBatch::truncated);
    outer:
    for (CandidateBatch batch : batches) {
      for (RetrievalCandidate candidate : batch.candidates()) {
        if (accepted >= query.budgets().totalCandidates()) {
          truncated = true;
          diagnostics.add(
              new RetrievalDiagnostic(
                  Optional.empty(),
                  RetrievalDiagnosticCode.SOURCE_TRUNCATED,
                  "normalized candidate budget reached"));
          break outer;
        }
        if (!confined(candidate, query)) {
          diagnostics.add(rejected(candidate, "candidate is outside the requested workspace"));
          continue;
        }
        if (!query.includeStale()
            && (candidate.freshness() == RetrievalFreshness.STALE
                || candidate.freshness() == RetrievalFreshness.RETRYABLE)) {
          diagnostics.add(
              new RetrievalDiagnostic(
                  Optional.of(candidate.retrievalSource()),
                  RetrievalDiagnosticCode.STALE_CANDIDATE,
                  "stale or retryable candidate was excluded"));
          continue;
        }
        Optional<String> objectKey = candidate.objectId().map(value -> "object:" + value);
        Optional<String> locationKey = preciseLocation(candidate);
        Group group =
            objectKey.map(byObjectId::get).orElseGet(() -> locationKey.map(byLocation::get).orElse(null));
        if (group == null) {
          group = new Group(candidate);
          groups.add(group);
        } else if (!group.compatible(candidate)) {
          diagnostics.add(rejected(candidate, "candidate identity conflicts with an existing object"));
          continue;
        } else {
          group.merge(candidate);
        }
        Group selectedGroup = group;
        objectKey.ifPresent(key -> byObjectId.putIfAbsent(key, selectedGroup));
        locationKey.ifPresent(key -> byLocation.putIfAbsent(key, selectedGroup));
        accepted++;
      }
    }

    for (Group group : groups) {
      precisePath(group.preferred)
          .map(gitEnrichment.evidenceByPath()::get)
          .ifPresent(group::addEvidence);
    }
    return new CandidatePreparation(
        groups.stream().map(Group::freeze).toList(), diagnostics, truncated);
  }

  private static boolean confined(RetrievalCandidate candidate, ValidatedKnowledgeQuery query) {
    if (candidate.belongsToVirtualProject()) {
      return candidate
          .virtualProjectId()
          .filter(query.virtualProjectId()::equals)
          .isPresent();
    }
    if (candidate.retrievalSource() == RetrievalSource.STRUCTURED_STORE
        || candidate.retrievalSource() == RetrievalSource.LUCENE_BM25) {
      return true;
    }
    if (!candidate.source().system().equals("engineering-workspace")) {
      return false;
    }
    String prefix = query.workspaceId() + ":";
    if (!candidate.source().locator().startsWith(prefix)) {
      return false;
    }
    String relative = candidate.source().locator().substring(prefix.length());
    try {
      Path resolved = query.workspaceRoot().resolve(relative).normalize();
      return !relative.isBlank() && resolved.startsWith(query.workspaceRoot());
    } catch (InvalidPathException invalid) {
      return false;
    }
  }

  private static Optional<String> preciseLocation(RetrievalCandidate candidate) {
    if (candidate.evidence().isEmpty()) {
      return Optional.empty();
    }
    Evidence first = candidate.evidence().get(0);
    return Optional.of(
        "location:"
            + first.source().system()
            + ":"
            + first.source().locator()
            + "#"
            + first.location());
  }

  private static Optional<String> precisePath(RetrievalCandidate candidate) {
    if (!candidate.source().system().equals("engineering-workspace")) {
      return Optional.empty();
    }
    String locator = candidate.source().locator();
    int separator = locator.indexOf(':');
    return Optional.of(separator >= 0 ? locator.substring(separator + 1) : locator);
  }

  private static RetrievalDiagnostic rejected(RetrievalCandidate candidate, String detail) {
    return new RetrievalDiagnostic(
        Optional.of(candidate.retrievalSource()),
        RetrievalDiagnosticCode.PROVENANCE_INCOMPLETE,
        detail);
  }

  private static int freshnessOrder(RetrievalFreshness freshness) {
    return switch (freshness) {
      case CURRENT_CANONICAL -> 0;
      case CURRENT_DERIVED -> 1;
      case STALE -> 2;
      case RETRYABLE -> 3;
      case UNVERIFIED -> 4;
    };
  }

  private static final class Group {
    private RetrievalCandidate preferred;
    private RetrievalCandidate rankingCandidate;
    private final Set<RetrievalSource> sources = new LinkedHashSet<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private boolean provenanceComplete;
    private Optional<String> virtualProjectId = Optional.empty();

    private Group(RetrievalCandidate candidate) {
      preferred = candidate;
      rankingCandidate = candidate;
      mergeDetails(candidate);
    }

    private boolean compatible(RetrievalCandidate candidate) {
      return preferred.objectId().isEmpty()
          || candidate.objectId().isEmpty()
          || (preferred.objectId().equals(candidate.objectId())
              && preferred.objectType() == candidate.objectType());
    }

    private void merge(RetrievalCandidate candidate) {
      if (preferred(candidate, preferred) == candidate) {
        preferred = candidate;
      }
      if (bestMatch(candidate, rankingCandidate) == candidate) {
        rankingCandidate = candidate;
      }
      mergeDetails(candidate);
    }

    private void mergeDetails(RetrievalCandidate candidate) {
      sources.add(candidate.retrievalSource());
      addEvidence(candidate.evidence());
      provenanceComplete |= candidate.provenanceComplete();
      if (candidate.virtualProjectId().isPresent()) {
        virtualProjectId = candidate.virtualProjectId();
      }
    }

    private void addEvidence(List<Evidence> additions) {
      for (Evidence item : additions) {
        if (!evidence.contains(item)) {
          evidence.add(item);
        }
      }
    }

    private FusedCandidate freeze() {
      return new FusedCandidate(
          preferred, rankingCandidate, sources, evidence, provenanceComplete, virtualProjectId);
    }

    private static RetrievalCandidate preferred(
        RetrievalCandidate left, RetrievalCandidate right) {
      Comparator<RetrievalCandidate> order =
          Comparator.comparingInt(
                  (RetrievalCandidate candidate) -> freshnessOrder(candidate.freshness()))
              .thenComparing(candidate -> !candidate.provenanceComplete())
              .thenComparingInt(RetrievalCandidate::sourceRank)
              .thenComparing(RetrievalCandidate::stableLocation);
      return order.compare(left, right) <= 0 ? left : right;
    }

    private static RetrievalCandidate bestMatch(
        RetrievalCandidate left, RetrievalCandidate right) {
      Comparator<RetrievalCandidate> order =
          Comparator.comparingInt(
                  (RetrievalCandidate candidate) -> candidate.matchCategory().ordinal())
              .thenComparingInt(RetrievalCandidate::sourceRank)
              .thenComparing(RetrievalCandidate::stableLocation);
      return order.compare(left, right) <= 0 ? left : right;
    }
  }
}
