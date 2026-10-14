package me.asu.kb.context;

import java.util.Objects;
import me.asu.kb.retrieval.KnowledgeRetriever;

/** Default deterministic coordinator behind the single public context-building seam. */
final class DefaultContextBuilder implements ContextBuilder {
  private final ContextCandidateResolver resolver;
  private final ContextCandidateOrganizer organizer;
  private final ContextBudgetPacker packer;

  DefaultContextBuilder(KnowledgeRetriever retriever, ContextRelationReader relationReader) {
    this(
        new ContextCandidateResolver(new ContextBuildCoordinator(retriever, relationReader)),
        new ContextCandidateOrganizer(),
        new ContextBudgetPacker());
  }

  DefaultContextBuilder(
      ContextCandidateResolver resolver,
      ContextCandidateOrganizer organizer,
      ContextBudgetPacker packer) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.organizer = Objects.requireNonNull(organizer, "organizer");
    this.packer = Objects.requireNonNull(packer, "packer");
  }

  @Override
  public EngineeringContext build(ContextRequest request) {
    ContextCandidateResolution resolution = resolver.resolve(request);
    ContextCandidateOrganization organization = organizer.organize(resolution);
    return packer.pack(request, organization);
  }
}
