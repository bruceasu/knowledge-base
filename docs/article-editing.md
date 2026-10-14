# 文章编辑与版本操作

## 适用范围

本文描述单工作区服务中由 WebUI 和 `/api/v2` 暴露的文章维护能力。搜索和问答结果默认只读；用户显式进入编辑流程后，才能修改正文或 Article metadata。接口不接受逻辑 workspace ID，也不提供工作区注册或切换。

## 存储位置

- 新建文章保存在服务工作区的 `document/articles/YYYY-MM-DD` 下，默认使用 Markdown。
- 浏览器上传内容保存在 `document/uploads` 下。
- 已登记 root 中受支持的文本文件可原地编辑，写入使用原子替换。
- revision 快照保存在 `document/.kb/history`，trash 保存在 `document/.kb/trash`。
- `.kb/history` 与 `.kb/trash` 是系统目录，不参与普通同步、重建索引、搜索或问答。

所有路径都受服务工作区边界检查约束。调用方不能通过请求字段选择另一个 workspace。

## 保存与冲突处理

保存正文会创建新的 current revision，并继承 Article 级 metadata。只修改 metadata 不创建内容 revision。

编辑请求提交打开文档时获得的 `expectedBaseHash`。若源文件已被外部修改，服务会尝试基于历史快照进行非重叠行级合并；出现重叠修改或缺少基线快照时返回 HTTP 409，并提供当前服务端内容和提交内容供用户处理。

文件写入与索引投影是两个明确状态。文件保存成功后，projection 状态可能是 `pending`、`indexed` 或 `failed`；失败状态可通过服务工作区的恢复或重建流程处理。

## 删除、恢复与维护

删除操作把源文件及其 history 移入服务工作区的 trash，不在原 root 留 tombstone。trash 默认保留 30 天。恢复操作不得覆盖已有目标文件，并会生成新的 current revision。

Root purge 按文件执行；部分失败不会回滚已成功处理的文件，root 会记录失败状态并允许重试。

`POST /api/v2/maintenance/cleanup` 清理过期 trash 和符合保留策略的非 current revision，永远不会删除 live Article 的 current revision。服务不内置调度器；需要周期清理时，由部署环境使用 cron、Windows Task Scheduler 或服务平台任务显式调用该接口。

## HTTP 接口

- `GET /api/v2/documents`：按有界分页读取文档列表。
- `POST /api/v2/documents`：在托管文章目录中创建文档。
- `GET /api/v2/documents/{id}`：查看正文、metadata、hash、current revision 和 projection 状态。
- `PATCH /api/v2/documents/{id}`：保存正文或只更新 metadata。
- `GET /api/v2/documents/{id}/revisions`：查看 revision 历史。
- `POST /api/v2/documents/{id}/revisions/{revision}/restore`：以新 revision 恢复历史内容。
- `DELETE /api/v2/documents/{id}`：移动到 trash。
- `GET /api/v2/trash`、`POST /api/v2/trash/{id}/restore`：查询和恢复 trash。
- `POST /api/v2/maintenance/cleanup`：显式执行维护清理。
- `GET /api/v2/maintenance/events`：查询维护事件。

请求不得包含 `workspaceId`、`workspace`、`workspace_id`、`X-Workspace-Id` 或 `X-Workspace`。这些字段会得到 HTTP 400 和 `OBSOLETE_WORKSPACE_FIELD`。
