package me.asu.kb.runtime;

import me.asu.kb.agent.KnowledgeAgentFacade;
import me.asu.kb.context.ContextBuilder;
import me.asu.kb.ingestion.KnowledgeProjectionIngestion;
import me.asu.kb.read.KnowledgeObjectReader;
import me.asu.kb.retrieval.KnowledgeRetriever;
import me.asu.kb.retrieval.VirtualProjectIndexManager;
import me.asu.kb.trace.KnowledgeRelationTracer;

/** Application-only view shared by transports without exposing persistence or tool processes. */
public interface SingleWorkspaceApplication {
  /** All transport-triggered application work must enter through this coordinator. */
  ServiceOperationCoordinator operationCoordinator();

  SingleWorkspaceDocumentApplication documents();

  VirtualProjectApplication virtualProjects();

  VirtualProjectIndexManager virtualProjectIndexes();

  KnowledgeProjectionIngestion projectionIngestion();

  KnowledgeRetriever retriever();

  KnowledgeObjectReader objectReader();

  KnowledgeRelationTracer relationTracer();

  ContextBuilder contextBuilder();

  KnowledgeAgentFacade agentFacade();
}
