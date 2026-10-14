package me.asu.kb.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalSource;

/** Applies stable identity deduplication, provenance aggregation, and fixed priority ordering. */
final class ContextCandidateOrganizer {
  private static final Comparator<Evidence> EVIDENCE_ORDER =
      Comparator.comparing((Evidence evidence) -> evidence.type().name())
          .thenComparing(evidence -> evidence.source().system())
          .thenComparing(evidence -> evidence.source().locator())
          .thenComparing(evidence -> evidence.source().revision().orElse(""))
          .thenComparing(Evidence::location)
          .thenComparingDouble(Evidence::confidence);

  ContextCandidateOrganization organize(ContextCandidateResolution resolution) {
    Objects.requireNonNull(resolution, "resolution");
    if (resolution.target().isEmpty()) {
      return new ContextCandidateOrganization(
          resolution.request(),
          List.of(),
          resolution.diagnostics(),
          resolution.omissions(),
          resolution.complete());
    }

    List<ContextCandidate> input = new ArrayList<>(1 + resolution.related().size());
    input.add(resolution.target().orElseThrow());
    input.addAll(resolution.related());
    List<CandidateGroup> groups = groupCandidates(input);
    List<OmissionRecord> omissions = new ArrayList<>(resolution.omissions());
    List<SelectedContextCandidate> selected = new ArrayList<>(groups.size());
    for (CandidateGroup group : groups) {
      selected.add(merge(group, omissions));
    }
    selected.sort(ContextCandidateOrganizer::compareSelected);
    return new ContextCandidateOrganization(
        resolution.request(),
        selected,
        resolution.diagnostics(),
        omissions,
        resolution.complete());
  }

  private static List<CandidateGroup> groupCandidates(List<ContextCandidate> candidates) {
    List<CandidateGroup> groups = new ArrayList<>();
    for (ContextCandidate candidate : candidates) {
      List<Integer> matchingIndexes = new ArrayList<>();
      for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
        if (groups.get(groupIndex).matches(candidate)) {
          matchingIndexes.add(groupIndex);
        }
      }
      if (matchingIndexes.isEmpty()) {
        groups.add(new CandidateGroup(candidate));
        continue;
      }

      CandidateGroup destination = groups.get(matchingIndexes.get(0));
      destination.add(candidate);
      for (int index = matchingIndexes.size() - 1; index > 0; index--) {
        int mergedGroupIndex = matchingIndexes.get(index);
        destination.merge(groups.get(mergedGroupIndex));
        groups.remove(mergedGroupIndex);
      }
    }
    return groups;
  }

  private static SelectedContextCandidate merge(
      CandidateGroup group, List<OmissionRecord> omissions) {
    ContextCandidate representative =
        group.members().stream().min(ContextCandidateOrganizer::compareRepresentative).orElseThrow();
    boolean target = group.members().stream().anyMatch(ContextCandidate::target);
    ContextSelectionReason reason = target ? ContextSelectionReason.TARGET : reasonFor(representative);

    EnumSet<RetrievalSource> sources = EnumSet.noneOf(RetrievalSource.class);
    Set<Evidence> evidenceSet = new LinkedHashSet<>();
    for (ContextCandidate member : group.members()) {
      sources.addAll(member.contributingSources());
      evidenceSet.addAll(member.evidence());
      if (member != representative) {
        omissions.add(
            new OmissionRecord(
                OmissionReason.DUPLICATE_MERGED,
                member.objectId(),
                Optional.of(member.source()),
                "candidate merged by stable object identity or source location"));
      }
    }
    List<Evidence> evidence = evidenceSet.stream().sorted(EVIDENCE_ORDER).toList();
    double confidence =
        evidence.stream().mapToDouble(Evidence::confidence).max().orElse(0.0d);
    ContextCandidate merged =
        new ContextCandidate(
            representative.workspaceId(),
            representative.objectId(),
            representative.objectType(),
            representative.title(),
            representative.content(),
            representative.source(),
            representative.knowledgeClass(),
            representative.freshness(),
            sources,
            evidence,
            target);
    return new SelectedContextCandidate(
        merged, reason, new ContextProvenance(sources, evidence, confidence));
  }

  private static int compareRepresentative(ContextCandidate left, ContextCandidate right) {
    int comparison = Boolean.compare(right.target(), left.target());
    if (comparison != 0) {
      return comparison;
    }
    comparison = Integer.compare(priority(reasonFor(left)), priority(reasonFor(right)));
    if (comparison != 0) {
      return comparison;
    }
    comparison = Integer.compare(freshnessPriority(left.freshness()), freshnessPriority(right.freshness()));
    if (comparison != 0) {
      return comparison;
    }
    comparison = Boolean.compare(right.content().isPresent(), left.content().isPresent());
    if (comparison != 0) {
      return comparison;
    }
    return stableKey(left).compareTo(stableKey(right));
  }

  private static int compareSelected(
      SelectedContextCandidate left, SelectedContextCandidate right) {
    int comparison =
        Integer.compare(priority(left.selectionReason()), priority(right.selectionReason()));
    if (comparison != 0) {
      return comparison;
    }
    comparison =
        Integer.compare(
            freshnessPriority(left.candidate().freshness()),
            freshnessPriority(right.candidate().freshness()));
    if (comparison != 0) {
      return comparison;
    }
    return stableKey(left.candidate()).compareTo(stableKey(right.candidate()));
  }

  private static ContextSelectionReason reasonFor(ContextCandidate candidate) {
    if (candidate.target()) {
      return ContextSelectionReason.TARGET;
    }
    ObjectType type = candidate.objectType();
    if (type == ObjectType.REQUIREMENT || type == ObjectType.DESIGN) {
      return ContextSelectionReason.DIRECT_REQUIREMENT_DESIGN_DECISION;
    }
    if (type == ObjectType.CODE_SYMBOL) {
      return ContextSelectionReason.DIRECT_CODE_SYMBOL;
    }
    if (type == ObjectType.TEST) {
      return ContextSelectionReason.CORRESPONDING_TEST;
    }
    if (type == ObjectType.COMMIT) {
      return ContextSelectionReason.MATCHING_GIT_EVIDENCE;
    }
    return ContextSelectionReason.OTHER_DIRECT_RELATION;
  }

  private static int priority(ContextSelectionReason reason) {
    return switch (reason) {
      case TARGET -> 0;
      case DIRECT_REQUIREMENT_DESIGN_DECISION -> 1;
      case DIRECT_CODE_SYMBOL -> 2;
      case CORRESPONDING_TEST -> 3;
      case MATCHING_GIT_EVIDENCE -> 4;
      case OTHER_DIRECT_RELATION -> 5;
    };
  }

  private static int freshnessPriority(RetrievalFreshness freshness) {
    return switch (freshness) {
      case CURRENT_CANONICAL -> 0;
      case CURRENT_DERIVED -> 1;
      case STALE -> 2;
      case RETRYABLE -> 3;
      case UNVERIFIED -> 4;
    };
  }

  private static String stableKey(ContextCandidate candidate) {
    return candidate.objectId().map(value -> "id:" + value).orElseGet(() -> sourceKey(candidate.source()));
  }

  private static String sourceKey(SourceRef source) {
    return "source:"
        + source.system()
        + "\u0000"
        + source.locator()
        + "\u0000"
        + source.revision().orElse("");
  }

  private static boolean sameIdentity(ContextCandidate left, ContextCandidate right) {
    boolean sameObject =
        left.objectId().isPresent()
            && right.objectId().isPresent()
            && left.objectId().orElseThrow().equals(right.objectId().orElseThrow());
    return sameObject || left.source().equals(right.source());
  }

  private static final class CandidateGroup {
    private final List<ContextCandidate> members = new ArrayList<>();

    private CandidateGroup(ContextCandidate first) {
      members.add(first);
    }

    private boolean matches(ContextCandidate candidate) {
      return members.stream().anyMatch(member -> sameIdentity(member, candidate));
    }

    private void add(ContextCandidate candidate) {
      members.add(candidate);
    }

    private void merge(CandidateGroup other) {
      members.addAll(other.members);
    }

    private List<ContextCandidate> members() {
      return members;
    }
  }
}
