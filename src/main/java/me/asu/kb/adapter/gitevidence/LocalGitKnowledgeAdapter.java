package me.asu.kb.adapter.gitevidence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded, local-only implementation of the public Git evidence seam. */
public final class LocalGitKnowledgeAdapter implements GitKnowledgeAdapter {
  private static final Comparator<GitFileChange> CHANGE_ORDER =
      Comparator.comparing(LocalGitKnowledgeAdapter::primaryPath)
          .thenComparing(change -> change.kind().name())
          .thenComparing(change -> change.oldPath().orElse(""))
          .thenComparing(change -> change.newPath().orElse(""));

  private final GitEvidenceSettings settings;
  private final GitRequestValidator validator;
  private final GitRepositoryProbe probe;
  private final GitHistoryReader historyReader;
  private final GitEvidenceProjector projector;

  public LocalGitKnowledgeAdapter(
      Map<String, Path> workspaceRoots, GitEvidenceSettings settings) {
    this(workspaceRoots, settings, new ProcessBuilderGitLauncher());
  }

  LocalGitKnowledgeAdapter(
      Map<String, Path> workspaceRoots,
      GitEvidenceSettings settings,
      GitProcessLauncher launcher) {
    this.settings = Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(launcher, "launcher");
    this.validator =
        new GitRequestValidator(
            workspaceRoots, settings.defaultMaxCommits(), settings.defaultMaxChanges());
    this.probe = new ProcessGitRepositoryProbe(settings, launcher);
    this.historyReader = new GitHistoryReader(settings, launcher);
    this.projector = new GitEvidenceProjector();
  }

  @Override
  public GitEvidenceResult inspect(GitEvidenceRequest request) {
    if (settings.executable().isBlank()) {
      return failed(
          unknownProfile(),
          GitEvidenceDiagnosticCode.TOOL_UNAVAILABLE,
          "git is not configured");
    }
    GitExecutionBudget budget =
        new GitExecutionBudget(settings.timeout(), settings.maxOutputBytes());
    GitToolProfile profile = unknownProfile();
    final ValidatedGitRequest validated;
    try {
      validated = validator.validate(request, probe, budget);
      profile = observedProfile(validated.repository());
    } catch (GitValidationException error) {
      return failed(profile, error.code(), error.getMessage());
    } catch (GitProbeException error) {
      return failed(profile, error.code(), error.getMessage());
    }

    List<RawGitCommit> rawCommits = new ArrayList<>();
    List<GitEvidenceDiagnostic> diagnostics = new ArrayList<>();
    if (validated.commitTruncated()) {
      diagnostics.add(
          new GitEvidenceDiagnostic(
              GitEvidenceDiagnosticCode.COMMIT_TRUNCATED,
              "commit range exceeded the effective commit budget"));
    }
    for (String objectId : validated.commitObjectIds()) {
      try {
        rawCommits.add(
            historyReader.readCommit(
                validated.repository().gitRoot(),
                objectId,
                validated.repositoryRelativeFilters(),
                budget));
      } catch (GitProbeException error) {
        diagnostics.add(new GitEvidenceDiagnostic(error.code(), error.getMessage()));
        break;
      }
    }

    final List<RawGitCommit> ordered;
    try {
      ordered = topologicalOldToNew(rawCommits);
    } catch (GitProbeException error) {
      diagnostics.add(new GitEvidenceDiagnostic(error.code(), error.getMessage()));
      return new GitEvidenceResult(List.of(), profile, diagnostics, false);
    }

    List<GitCommitEvidence> commits = new ArrayList<>();
    for (RawGitCommit rawCommit : ordered) {
      try {
        GitEvidenceProjector.ProjectedCommit projected = projector.project(validated, rawCommit);
        diagnostics.addAll(projected.diagnostics());
        commits.add(withSortedChanges(projected.commit()));
      } catch (GitProbeException error) {
        diagnostics.add(new GitEvidenceDiagnostic(error.code(), error.getMessage()));
        break;
      }
    }
    ChangeBounded bounded = boundChanges(commits, validated.changeLimit());
    if (bounded.truncated()) {
      diagnostics.add(
          new GitEvidenceDiagnostic(
              GitEvidenceDiagnosticCode.CHANGE_TRUNCATED,
              "file changes exceeded the effective change budget"));
    }
    return new GitEvidenceResult(
        bounded.commits(), profile, diagnostics, diagnostics.isEmpty());
  }

  private static List<RawGitCommit> topologicalOldToNew(List<RawGitCommit> commits)
      throws GitProbeException {
    Map<String, RawGitCommit> byId = new HashMap<>();
    for (RawGitCommit commit : commits) {
      if (byId.putIfAbsent(commit.objectId(), commit) != null) {
        throw malformed("Git history contains a duplicate commit object ID");
      }
    }
    Map<String, Integer> indegrees = new HashMap<>();
    Map<String, List<String>> children = new HashMap<>();
    for (RawGitCommit commit : commits) {
      int indegree = 0;
      Set<String> selectedParents = new HashSet<>();
      for (String parent : commit.parentObjectIds()) {
        if (byId.containsKey(parent) && selectedParents.add(parent)) {
          indegree++;
          children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(commit.objectId());
        }
      }
      indegrees.put(commit.objectId(), indegree);
    }
    PriorityQueue<String> ready = new PriorityQueue<>();
    indegrees.forEach(
        (objectId, indegree) -> {
          if (indegree == 0) {
            ready.add(objectId);
          }
        });
    List<RawGitCommit> ordered = new ArrayList<>(commits.size());
    while (!ready.isEmpty()) {
      String objectId = ready.remove();
      ordered.add(byId.get(objectId));
      List<String> next = new ArrayList<>(children.getOrDefault(objectId, List.of()));
      next.sort(String::compareTo);
      for (String child : next) {
        int remaining = indegrees.compute(child, (ignored, value) -> value - 1);
        if (remaining == 0) {
          ready.add(child);
        }
      }
    }
    if (ordered.size() != commits.size()) {
      throw malformed("selected Git commit graph is not acyclic");
    }
    return List.copyOf(ordered);
  }

  private static GitCommitEvidence withSortedChanges(GitCommitEvidence commit) {
    List<GitFileChange> changes = new ArrayList<>(commit.changes());
    changes.sort(CHANGE_ORDER);
    return copyWithChanges(commit, changes);
  }

  private static ChangeBounded boundChanges(List<GitCommitEvidence> commits, int maximum) {
    int remaining = maximum;
    boolean truncated = false;
    List<GitCommitEvidence> bounded = new ArrayList<>(commits.size());
    for (GitCommitEvidence commit : commits) {
      if (commit.changes().size() <= remaining) {
        bounded.add(commit);
        remaining -= commit.changes().size();
        continue;
      }
      bounded.add(copyWithChanges(commit, commit.changes().subList(0, remaining)));
      truncated = true;
      remaining = 0;
    }
    return new ChangeBounded(bounded, truncated);
  }

  private static GitCommitEvidence copyWithChanges(
      GitCommitEvidence commit, List<GitFileChange> changes) {
    return new GitCommitEvidence(
        commit.objectId(),
        commit.parentObjectIds(),
        commit.authorName(),
        commit.authorEmail(),
        commit.authoredAt(),
        commit.committerName(),
        commit.committerEmail(),
        commit.committedAt(),
        commit.subject(),
        changes,
        commit.evidence());
  }

  private static String primaryPath(GitFileChange change) {
    return change.newPath().orElseGet(() -> change.oldPath().orElseThrow());
  }

  private static GitToolProfile unknownProfile() {
    return new GitToolProfile(
        GitCommandRunner.PROFILE_ID, Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static GitToolProfile observedProfile(GitRepositoryFacts facts) {
    return new GitToolProfile(
        GitCommandRunner.PROFILE_ID,
        Optional.of(facts.effectiveVersion()),
        Optional.of(facts.objectFormat()),
        Optional.of(facts.shallowRepository()));
  }

  private static GitEvidenceResult failed(
      GitToolProfile profile, GitEvidenceDiagnosticCode code, String detail) {
    return new GitEvidenceResult(
        List.of(), profile, List.of(new GitEvidenceDiagnostic(code, detail)), false);
  }

  private static GitProbeException malformed(String detail) {
    return new GitProbeException(GitEvidenceDiagnosticCode.MALFORMED_OUTPUT, detail);
  }

  private record ChangeBounded(List<GitCommitEvidence> commits, boolean truncated) {
    private ChangeBounded {
      commits = List.copyOf(commits);
    }
  }
}
