package me.asu.kb.runtime;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.EngineeringWorkspaceCatalog;
import me.asu.kb.workspace.ImmutableEngineeringWorkspaceCatalog;

/** Immutable, side-effect-free configuration for the single-workspace service. */
public final class SingleWorkspaceServiceConfig {
  public static final String INTERNAL_WORKSPACE_ID = "service";
  public static final String CLI_WORKSPACE_ROOT = "--workspace-root";
  public static final String TOML_WORKSPACE_ROOT = "workspace_root";

  private static final List<String> OBSOLETE_CLI_KEYS =
      List.of("--workspace", "--workspace-id", "--server-workspace", "--server-root");
  private static final List<String> OBSOLETE_TOML_KEYS =
      List.of(
          "workspace",
          "workspace_id",
          "workspaces",
          "server_workspace",
          "server_root",
          "server_roots");

  private final Path workspaceRoot;
  private final EngineeringWorkspace workspace;
  private final EngineeringWorkspaceCatalog catalog;
  private final Map<String, Path> workspaceRoots;

  private SingleWorkspaceServiceConfig(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.workspace = new EngineeringWorkspace(INTERNAL_WORKSPACE_ID, this.workspaceRoot);
    this.catalog = new ImmutableEngineeringWorkspaceCatalog(List.of(workspace));
    this.workspaceRoots = Map.of(INTERNAL_WORKSPACE_ID, this.workspaceRoot);
  }

  /**
   * Parses only the workspace boundary owned by the new service.
   *
   * <p>Unrelated CLI/TOML fields are left for their owning configuration modules. This method does
   * not probe or create the root.
   */
  public static SingleWorkspaceServiceConfig parse(
      String[] arguments, Map<String, ?> tomlValues) {
    String[] safeArguments = arguments == null ? new String[0] : arguments.clone();
    Map<String, ?> safeTomlValues = tomlValues == null ? Map.of() : tomlValues;

    rejectObsoleteCliKeys(safeArguments);
    rejectObsoleteTomlKeys(safeTomlValues);

    List<String> configuredRoots = new ArrayList<>(2);
    collectCliRoots(safeArguments, configuredRoots);
    if (safeTomlValues.containsKey(TOML_WORKSPACE_ROOT)) {
      configuredRoots.add(configText(safeTomlValues.get(TOML_WORKSPACE_ROOT)));
    }
    if (configuredRoots.size() != 1) {
      throw new IllegalArgumentException(
          "exactly one workspace root is required; use --workspace-root or workspace_root");
    }

    String configuredRoot = configuredRoots.get(0);
    if (configuredRoot == null || configuredRoot.isBlank()) {
      throw new IllegalArgumentException("workspace root must not be blank");
    }
    try {
      return new SingleWorkspaceServiceConfig(Path.of(configuredRoot));
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("workspace root is invalid", error);
    }
  }

  public Path workspaceRoot() {
    return workspaceRoot;
  }

  public EngineeringWorkspace workspace() {
    return workspace;
  }

  public EngineeringWorkspaceCatalog workspaceCatalog() {
    return catalog;
  }

  public Map<String, Path> workspaceRoots() {
    return workspaceRoots;
  }

  /** Resolves a service-owned layout path while rejecting absolute and parent-escaping inputs. */
  public Path resolveInsideWorkspace(Path relativePath) {
    Objects.requireNonNull(relativePath, "relativePath");
    if (relativePath.isAbsolute()) {
      throw new IllegalArgumentException("workspace-relative path must not be absolute");
    }
    Path resolved = workspaceRoot.resolve(relativePath).normalize();
    if (!resolved.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("workspace-relative path escapes the workspace root");
    }
    return resolved;
  }

  private static void collectCliRoots(String[] arguments, List<String> configuredRoots) {
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      if (CLI_WORKSPACE_ROOT.equals(argument)) {
        if (index + 1 >= arguments.length || arguments[index + 1] == null) {
          throw new IllegalArgumentException("missing value for --workspace-root");
        }
        configuredRoots.add(arguments[++index]);
      } else if (argument != null && argument.startsWith(CLI_WORKSPACE_ROOT + "=")) {
        configuredRoots.add(argument.substring((CLI_WORKSPACE_ROOT + "=").length()));
      }
    }
  }

  private static void rejectObsoleteCliKeys(String[] arguments) {
    for (String argument : arguments) {
      if (argument == null) {
        continue;
      }
      for (String obsolete : OBSOLETE_CLI_KEYS) {
        if (argument.equals(obsolete) || argument.startsWith(obsolete + "=")) {
          throw obsoleteField(obsolete);
        }
      }
    }
  }

  private static void rejectObsoleteTomlKeys(Map<String, ?> values) {
    for (String obsolete : OBSOLETE_TOML_KEYS) {
      if (values.containsKey(obsolete)) {
        throw obsoleteField(obsolete);
      }
    }
  }

  private static IllegalArgumentException obsoleteField(String field) {
    return new IllegalArgumentException(
        "obsolete workspace field " + field + "; use --workspace-root or workspace_root");
  }

  private static String configText(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("workspace_root must be a string");
    }
    return text;
  }
}
