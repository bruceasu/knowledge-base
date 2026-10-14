package me.asu.kb.agent;

/**
 * Transport-independent application boundary for the six read-only engineering-knowledge tools.
 * Implementations map these stable values to existing application modules; transports must not
 * bypass this interface.
 */
public interface KnowledgeAgentFacade {

  AgentToolValues.ToolResponse<AgentToolValues.KnowledgeSearchData> knowledgeSearch(
      AgentToolValues.KnowledgeSearchRequest request);

  AgentToolValues.ToolResponse<AgentToolValues.KnowledgeGetData> knowledgeGet(
      AgentToolValues.KnowledgeGetRequest request);

  AgentToolValues.ToolResponse<AgentToolValues.CodeSymbolData> codeSymbol(
      AgentToolValues.CodeSymbolRequest request);

  AgentToolValues.ToolResponse<AgentToolValues.CodeSearchData> codeSearch(
      AgentToolValues.CodeSearchRequest request);

  AgentToolValues.ToolResponse<AgentToolValues.TraceData> trace(
      AgentToolValues.TraceRequest request);

  AgentToolValues.ToolResponse<AgentToolValues.BuildContextData> buildContext(
      AgentToolValues.BuildContextRequest request);
}
