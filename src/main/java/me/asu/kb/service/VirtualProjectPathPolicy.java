package me.asu.kb.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable local path policy for virtual-project resources.
 *
 * <p>The policy resolves the requested path through the nearest existing ancestor before
 * evaluating rules. This makes a missing child below a symbolic-link directory use the same
 * canonical target as an existing child. Forbidden rules are evaluated before allow rules, and
 * paths outside the workspace require an explicit allow rule.
 *
 * <p>This class intentionally does not retain a mutable configuration reference. A policy is a
 * startup snapshot and must be recreated when configuration changes.
 */
public final class VirtualProjectPathPolicy {
  private static final String INVALID_DIAGNOSTIC = "path request is invalid";
  private static final String UNRESOLVED_DIAGNOSTIC = "path target cannot be safely resolved";
  private static final String FORBIDDEN_DIAGNOSTIC = "path is forbidden by local policy";
  private static final String EXTERNAL_DIAGNOSTIC = "path is outside the allowed local scope";

  private final Rule workspace;
  private final List<Rule> allowed;
  private final List<Rule> forbidden;

  /** Creates a startup path-policy snapshot from absolute workspace, allow, and forbid paths. */
  public VirtualProjectPathPolicy(
      Path workspaceRoot, List<Path> allowedPaths, List<Path> forbiddenPaths) {
    this.workspace = configuredRule(workspaceRoot, true, "workspace root");
    this.allowed = configuredRules(allowedPaths, "allow rule");
    this.forbidden = configuredRules(forbiddenPaths, "forbid rule");
  }

  /** Creates a policy with no external allow rules and therefore workspace-only access. */
  public VirtualProjectPathPolicy(Path workspaceRoot) {
    this(workspaceRoot, List.of(), List.of());
  }

  /** Returns the immutable canonical workspace boundary captured by this policy. */
  public Path workspaceRoot() {
    return workspace.path();
  }

  /** Returns canonical allow rules captured at construction time. */
  public List<Path> allowedPaths() {
    return allowed.stream().map(Rule::path).toList();
  }

  /** Returns canonical forbid rules captured at construction time. */
  public List<Path> forbiddenPaths() {
    return forbidden.stream().map(Rule::path).toList();
  }

  /**
   * Validates a requested path and returns its canonical target only when access is allowed.
   * Denied results deliberately do not include the requested or resolved local path.
   */
  public PathValidation validate(Path requested) {
    if (requested == null || !requested.isAbsolute()) {
      return denied(DenialReason.INVALID_PATH, INVALID_DIAGNOSTIC);
    }

    Optional<Path> canonical = resolveCanonical(requested);
    if (canonical.isEmpty()) {
      return denied(DenialReason.UNRESOLVED_TARGET, UNRESOLVED_DIAGNOSTIC);
    }
    Path target = canonical.orElseThrow();

    if (forbidden.stream().anyMatch(rule -> rule.matches(target))) {
      return denied(DenialReason.FORBIDDEN, FORBIDDEN_DIAGNOSTIC);
    }

    boolean insideWorkspace = workspace.matches(target);
    boolean explicitlyAllowed = allowed.stream().anyMatch(rule -> rule.matches(target));
    if (!insideWorkspace && !explicitlyAllowed) {
      return denied(DenialReason.OUTSIDE_ALLOW_LIST, EXTERNAL_DIAGNOSTIC);
    }
    return new PathValidation(true, Optional.of(target), DenialReason.NONE, "");
  }

  /** Returns a canonical allowed path or a sanitized policy exception. */
  public Path requireAllowed(Path requested) {
    PathValidation validation = validate(requested);
    if (!validation.allowed()) {
      throw new PathPolicyViolationException(validation.reason(), validation.diagnostic());
    }
    return validation.canonicalPath().orElseThrow();
  }

  /** Returns whether the requested path is allowed under this policy snapshot. */
  public boolean allows(Path requested) {
    return validate(requested).allowed();
  }

  private static List<Rule> configuredRules(List<Path> paths, String label) {
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    List<Rule> rules = new ArrayList<>(paths.size());
    for (Path path : paths) {
      rules.add(configuredRule(path, false, label));
    }
    return List.copyOf(rules);
  }

  private static Rule configuredRule(Path path, boolean forceDirectory, String label) {
    if (path == null || !path.isAbsolute()) {
      throw new IllegalArgumentException(label + " must be an absolute path");
    }
    Optional<Path> canonical = resolveCanonical(path);
    if (canonical.isEmpty()) {
      throw new IllegalArgumentException(label + " cannot be safely resolved");
    }
    boolean directory = forceDirectory || Files.isDirectory(path);
    return new Rule(canonical.orElseThrow(), directory);
  }

  /** Resolves existing paths and missing descendants without following an unresolvable link. */
  private static Optional<Path> resolveCanonical(Path requested) {
    final Path normalized;
    try {
      normalized = requested.toAbsolutePath().normalize();
    } catch (InvalidPathException | UnsupportedOperationException error) {
      return Optional.empty();
    }

    try {
      Path existing = normalized;
      while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        Path parent = existing.getParent();
        if (parent == null || parent.equals(existing)) {
          return Optional.empty();
        }
        existing = parent;
      }
      Path canonicalExisting = existing.toRealPath();
      return Optional.of(canonicalExisting.resolve(existing.relativize(normalized)).normalize());
    } catch (IOException | SecurityException error) {
      return Optional.empty();
    }
  }

  private static PathValidation denied(DenialReason reason, String diagnostic) {
    return new PathValidation(false, Optional.empty(), reason, diagnostic);
  }

  private record Rule(Path path, boolean directory) {
    private Rule {
      Objects.requireNonNull(path, "path");
    }

    private boolean matches(Path candidate) {
      return directory ? candidate.equals(path) || candidate.startsWith(path) : candidate.equals(path);
    }
  }

  /** Sanitized result of a local path-policy check. */
  public record PathValidation(
      boolean allowed, Optional<Path> canonicalPath, DenialReason reason, String diagnostic) {
    public PathValidation {
      Objects.requireNonNull(canonicalPath, "canonicalPath");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(diagnostic, "diagnostic");
      if (allowed != (reason == DenialReason.NONE)) {
        throw new IllegalArgumentException("path validation outcome and reason disagree");
      }
      if (allowed != canonicalPath.isPresent()) {
        throw new IllegalArgumentException("allowed path validation must include its canonical path");
      }
    }
  }

  /** Stable, path-free reasons that callers can map to HTTP or MCP errors. */
  public enum DenialReason {
    NONE,
    INVALID_PATH,
    UNRESOLVED_TARGET,
    FORBIDDEN,
    OUTSIDE_ALLOW_LIST
  }

  /** Exception whose message is safe to return to an untrusted caller. */
  public static final class PathPolicyViolationException extends IllegalArgumentException {
    private final DenialReason reason;

    public PathPolicyViolationException(DenialReason reason, String diagnostic) {
      super(diagnostic);
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DenialReason reason() {
      return reason;
    }
  }
}
