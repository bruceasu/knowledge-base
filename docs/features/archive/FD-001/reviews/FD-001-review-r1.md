# FD-001 独立审查 r1

- 结论：changes-requested；第 1 次 Reviewer outcome。
- Reviewer session：kb-fd001-reviewer-20261030-7e42a1。
- 来源事件：FD-001-000004-implementation-ready（本 session 已认领）。
- 审查基线：e1a45a0..42f8e83；未提交 FD 更新来自受管理 handoff。
- 范围：FD 验收 1–8、秘书 API 1.0、稳定规格、实际代码与部署模板；没有评估可选 fd-test 制品。

## 必须修复

1. **P2：索引不完整被误报为完整搜索。** `src/main/java/me/asu/kb/service/PersonalReadService.java:124` 仅用命中数判断 complete。候选 projection 非 INDEXED 虽然 metadata 标为 stale，但若资料尚未进入索引，搜索会返回空 items、complete=true、无 diagnostics。契约第 5 节要求来源失败/无法完成时 complete=false，部分成功提供诊断，完全无法检索返回 503；FD 验收 1、5 要求区分保存与可查询。请检查调用者可见且符合过滤条件的投影状态，将缺失/失败投影计入完整性与受控诊断，不泄漏授权范围外的状态；正常 limit 截断保持独立语义。

2. **P2：确定拒绝的笔记请求会永久锁住编辑器。** `webui/personal.js:53–70`（bundled 同副本）把所有 POST 错误当作结果未知。输入 21 个短标签或单个超过 100 字符标签，前端校验通过并保存 pendingNote；后端确定返回 400，客户端却每 10 秒重复 GET 404、POST 400，字段一直 disabled，刷新也恢复死循环。违背 Work Item 6.1 错误恢复要求。请区别确定未接受的校验错误和网络/5xx 结果未知：前者保留可编辑草稿、清除待确认操作、停止轮询；后者保留原键核对。补齐前端标签校验，但仍须处理后端确定拒绝。

3. **P2：正文与版本校验使用不同文件快照。** `src/main/java/me/asu/kb/service/PersonalReadService.java:141–149` 先由 kb.document 读取解码，再另开流计算 SHA-256。external_reference 文件不受 KB monitor 控制；若读取时文件为 B，校验前被外部恢复为已索引 A，校验通过却以 A 的 revision/contentHash 返回 B 正文。违反 FD 验收 2 和设计中同版本快照要求。请一次有界读取原始字节，对同一字节快照计算 hash 和解码，保留既有编码识别；第二次打开路径不能证明第一份正文版本。

## 已有静态证据

- 认证在正文解析前；秘书精确路径验证 Bearer，浏览器要求 loopback/代理密钥/身份和写入 Origin/CSRF。Caddy 清除外来身份头。
- ACL/集合/格式过滤在 Lucene top-k 前执行；游标签名绑定主体、资料、版本和期限。
- subject/key 唯一约束，202 前事务提交，确定文件路径和删除墓碑支撑重放恢复，保存与索引分别报告。
- 上传实际字节计量和 finally 清理、ZIP 展开预算、原件与正文分存、纯文本阅读均有实现。
- 迁移前 VACUUM INTO 和事务回滚；MCP 专属代码/依赖退役，共享领域模块保留。
- Worker 记录了 compile 成功及运行检查未执行。本 Reviewer 未重跑，不把静态证据称作端到端验收。

## 实际操作、未运行检查和风险

使用定向 Get-Content、rg、git diff --stat、生产文件 git diff、aiw fd show/claim、aiw fd emit --help，读取规则、FD、设计、Worker 报告、契约、规格和实现。最初查找 repo 根 skills/work-management.md 不存在，随后读取实际技能目录共享合同。第一次写报告因 reviews 目录尚不存在失败，emit 因缺少 artifact 拒绝；随后创建目录并重试，没有编辑 receipt。

未运行测试、编译、构建、网络、浏览器、代理配置校验。退回基于三个静态可达缺陷，不以未授权测试作为门槛。真实 100 MB/chunked、复杂文件、崩溃恢复、代理故障和跨机隔离仍需上线前运行验收。全局 KB monitor 覆盖解析作业可能拖延查询，保留性能风险。

修复后由 Worker 更新证据并重新发出 implementation-ready。可复用改进：交互状态机审查分别覆盖明确拒绝与结果未知；不在本 FD 修改通用技能。