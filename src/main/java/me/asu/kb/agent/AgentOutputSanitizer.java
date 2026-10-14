package me.asu.kb.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.EngineeringWorkspaceCatalog;

/** Converts output paths and diagnostics to values safe for the public tool contract. */
public final class AgentOutputSanitizer {
  private static final String SAFE_DIAGNOSTIC = "operation did not complete";
  private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("(?i).*[a-z]:[\\\\/].*");
  private static final Pattern SENSITIVE_DETAIL =
      Pattern.compile("(?is).*(exception|stack trace|select\\s|insert\\s|update\\s|delete\\s|--[a-z]).*");

  private final EngineeringWorkspaceCatalog workspaceCatalog;

  public AgentOutputSanitizer(EngineeringWorkspaceCatalog workspaceCatalog) {
    this.workspaceCatalog = Objects.requireNonNull(workspaceCatalog, "workspaceCatalog");
  }

  public String relativePath(String workspaceId, String rawPath) throws AgentRequestException {
    EngineeringWorkspace workspace =
        workspaceCatalog
            .find(workspaceId)
            .orElseThrow(
                () ->
                    new AgentRequestException(
                        AgentToolValues.Status.NOT_FOUND,
                        "WORKSPACE_NOT_FOUND",
                        "workspace is not registered"));
    if (rawPath == null || rawPath.isBlank()) {
      throw unsafePath();
    }
    try {
      Path supplied = Path.of(rawPath);
      Path root = workspace.root().toAbsolutePath().normalize();
      Path absolute = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
      if (!absolute.startsWith(root)) {
        throw unsafePath();
      }
      String relative = root.relativize(absolute).toString().replace('\\', '/');
      return relative.isEmpty() ? "." : relative;
    } catch (InvalidPathException failure) {
      throw unsafePath();
    }
  }

  public AgentToolValues.Diagnostic diagnostic(String code, String detail) {
    String safeCode = code == null || code.isBlank() ? "UNAVAILABLE" : code.trim();
    String safeDetail = isSafeDetail(detail) ? detail.trim() : SAFE_DIAGNOSTIC;
    return new AgentToolValues.Diagnostic(safeCode, safeDetail);
  }

  private static boolean isSafeDetail(String detail) {
    if (detail == null || detail.isBlank() || detail.length() > 4_096) {
      return false;
    }
    String trimmed = detail.trim();
    return !trimmed.startsWith("/")
        && !WINDOWS_ABSOLUTE.matcher(trimmed).matches()
        && !SENSITIVE_DETAIL.matcher(trimmed).matches();
  }

  private static AgentRequestException unsafePath() {
    return new AgentRequestException(
        AgentToolValues.Status.INTERNAL, "UNSAFE_OUTPUT", "output path is unavailable");
  }
}
