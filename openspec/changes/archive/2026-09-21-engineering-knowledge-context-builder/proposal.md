## Why

检索结果仍是候选集合；编码代理或工程人员需要的是在有限 token 预算内、围绕目标组织、能追溯来源且能解释遗漏的上下文。新增 Context Builder 可把当前知识、经验证关系、代码符号、测试和 Git 证据组织成确定性的工程上下文，而不让调用方自己拼检索、图扩展和裁剪规则。

## What Changes

- 新增深 Module `ContextBuilder`，以单一 `build(ContextRequest) -> EngineeringContext` Interface 生成有预算的、可溯源的工程上下文包。
- 使用 `KnowledgeRetriever` 和受控的 evidence-bearing 关系读取，定位目标、扩展有限关联、优先当前 canonical 数据、去重、排序并按确定性预算策略装配片段。
- 输出目标对象、相关需求/设计/决策、代码符号、测试、Git 证据、每项来源/置信度/freshness，以及因预算或数据限制而未包含内容的说明。
- 不调用 LLM 生成摘要或答案，不直连 Lucene/SQLite/`rg`/ctags/Git，不改 HTTP/CLI/MCP、schema、现有搜索或部署配置。

## Capabilities

### New Capabilities

- `engineering-context-builder`: 将经过检索和证据校验的工程知识组织成确定性、预算受限的上下文包。

### Modified Capabilities

- 无。

## Impact

本变更新增应用层 Context Builder、有限关系读取/包装和固定知识图夹具测试。它依赖完成的混合检索和 Store 关系证据语义；不会引入模型供应商、tokenizer 依赖、公开传输层或当前业务路径改变。MCP 是后续唯一计划中的外部消费者之一。
