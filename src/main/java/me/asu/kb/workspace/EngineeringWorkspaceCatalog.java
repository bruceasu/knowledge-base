package me.asu.kb.workspace;

import java.util.List;
import java.util.Optional;

/** Read-only application seam for registered engineering workspace descriptions. */
public interface EngineeringWorkspaceCatalog {
  Optional<EngineeringWorkspace> find(String workspaceId);

  List<EngineeringWorkspace> workspaces();
}
