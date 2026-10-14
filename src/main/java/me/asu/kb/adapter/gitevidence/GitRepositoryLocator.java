package me.asu.kb.adapter.gitevidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Locates the Git worktree containing a local path without registering or mutating the repository.
 *
 * <p>Failure to locate Git is deliberately represented as an empty result: Git provenance is
 * additive metadata and must not change path authorization or project membership.
 */
public final class GitRepositoryLocator {
  private final GitEvidenceSettings settings;

  public GitRepositoryLocator(GitEvidenceSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** Returns the nearest Git worktree root and the path relative to that root, when available. */
  public Optional<Location> locate(Path canonicalTarget) {
    Objects.requireNonNull(canonicalTarget, "canonicalTarget");
    Path target = canonicalTarget.toAbsolutePath().normalize();
    Path workingDirectory = existingDirectory(target);
    if (workingDirectory == null || settings.executable().isBlank()) {
      return Optional.empty();
    }

    try {
      GitRepositoryFacts facts =
          new ProcessGitRepositoryProbe(settings)
              .repositoryFacts(
                  workingDirectory,
                  new GitExecutionBudget(settings.timeout(), settings.maxOutputBytes()));
      Path repositoryRoot = facts.gitRoot();
      if (!target.startsWith(repositoryRoot)) {
        return Optional.empty();
      }
      String relativePath = repositoryRoot.relativize(target).toString().replace('\\', '/');
      if (relativePath.isEmpty()) {
        relativePath = ".";
      }
      return Optional.of(new Location(repositoryRoot.toString(), relativePath));
    } catch (GitProbeException | RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private static Path existingDirectory(Path target) {
    Path candidate = Files.isDirectory(target) ? target : target.getParent();
    while (candidate != null) {
      if (Files.isDirectory(candidate)) {
        try {
          return candidate.toRealPath();
        } catch (IOException | SecurityException ignored) {
          return null;
        }
      }
      candidate = candidate.getParent();
    }
    return null;
  }

  /** Stable, transport-friendly Git provenance for one referenced path. */
  public record Location(String repositoryRoot, String relativePath) {
    public Location {
      if (repositoryRoot == null || repositoryRoot.isBlank()) {
        throw new IllegalArgumentException("repositoryRoot must not be blank");
      }
      if (relativePath == null || relativePath.isBlank()) {
        throw new IllegalArgumentException("relativePath must not be blank");
      }
      if (relativePath.startsWith("/") || relativePath.contains("\\")) {
        throw new IllegalArgumentException("relativePath must use normalized repository-relative syntax");
      }
    }
  }
}
