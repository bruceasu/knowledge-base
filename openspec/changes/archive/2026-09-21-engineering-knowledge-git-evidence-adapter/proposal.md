## Why

工程上下文需要知道代码何时、由哪个不可变提交、以何种文件级变化出现或修改，但 Git 历史不能由调用方以任意命令和 commit message 解释来消费。封装 Git 为受限的来源证据 Adapter，可为后续追溯和上下文构建提供可重复的提交/文件变更事实，同时避免把自然语言提交信息升级为需求或实现关系。

## What Changes

- 新增 `GitKnowledgeAdapter`，通过受控请求/结果 seam 返回工作区 Git 仓库内的提交、父提交、受限文件变更、重命名和来源位置证据。
- 规定仓库根匹配、对象 ID 校验、有限提交范围、稳定排序、路径规范化、浅历史/缺失对象/非 Git 工作区和工具错误的诊断。
- 初版只产生提交与文件变更层面的 Git 事实/证据；不从 subject、author、diff 或文件名推断 `IMPLEMENTS`、`VERIFIED_BY` 或其他业务关系。
- 不修改 Git 工作区、远程、分支、SQLite/Lucene、HTTP/CLI 或现有同步路径；不执行 fetch、checkout、commit、merge、push 或删除操作。

## Capabilities

### New Capabilities

- `git-evidence-adapter`: 从受限本地 Git 历史中生成可追溯的提交与文件变更证据。

### Modified Capabilities

- 无。

## Impact

本变更预计新增一个只读本地 Git Adapter、请求/结果值类型和临时仓库夹具测试。它依赖已确认的工作区、来源与进程边界，但只返回内存证据；不新增网络能力或第三方依赖，不改变当前版本控制工作流、公开接口、SQLite schema 或 Lucene BM25 行为。
