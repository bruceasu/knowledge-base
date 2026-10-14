package me.asu.kb.adapter.gitevidence;

/** Public deep-module seam for bounded, local, read-only Git evidence inspection. */
@FunctionalInterface
public interface GitKnowledgeAdapter {

  GitEvidenceResult inspect(GitEvidenceRequest request);
}
