package me.asu.kb.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable workspace snapshot that owns descriptions only.
 *
 * <p>The caller owns all workspace resources and their lifecycle. This catalog never opens,
 * closes, creates, or probes a workspace root.
 */
public final class ImmutableEngineeringWorkspaceCatalog implements EngineeringWorkspaceCatalog {
  private final Map<String, EngineeringWorkspace> workspacesById;
  private final List<EngineeringWorkspace> orderedWorkspaces;

  public ImmutableEngineeringWorkspaceCatalog(List<EngineeringWorkspace> workspaces) {
    Objects.requireNonNull(workspaces, "workspaces");
    Map<String, EngineeringWorkspace> indexed = new LinkedHashMap<>();
    for (EngineeringWorkspace workspace : workspaces) {
      EngineeringWorkspace required = Objects.requireNonNull(workspace, "workspace");
      if (indexed.putIfAbsent(required.id(), required) != null) {
        throw new IllegalArgumentException("duplicate workspace id");
      }
    }
    List<EngineeringWorkspace> ordered = new ArrayList<>(indexed.values());
    ordered.sort(Comparator.comparing(EngineeringWorkspace::id));
    this.orderedWorkspaces = List.copyOf(ordered);

    Map<String, EngineeringWorkspace> sortedIndex = new LinkedHashMap<>();
    for (EngineeringWorkspace workspace : orderedWorkspaces) {
      sortedIndex.put(workspace.id(), workspace);
    }
    this.workspacesById = Map.copyOf(sortedIndex);
  }

  public static ImmutableEngineeringWorkspaceCatalog fromRoots(Map<String, Path> workspaceRoots) {
    Objects.requireNonNull(workspaceRoots, "workspaceRoots");
    List<EngineeringWorkspace> workspaces = new ArrayList<>(workspaceRoots.size());
    for (Map.Entry<String, Path> entry : workspaceRoots.entrySet()) {
      workspaces.add(new EngineeringWorkspace(entry.getKey(), entry.getValue()));
    }
    return new ImmutableEngineeringWorkspaceCatalog(workspaces);
  }

  @Override
  public Optional<EngineeringWorkspace> find(String workspaceId) {
    if (workspaceId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(workspacesById.get(workspaceId));
  }

  @Override
  public List<EngineeringWorkspace> workspaces() {
    return orderedWorkspaces;
  }
}
