# 个人秘书接入知识库：独立交接与 API 契约

日期：2026-10-29。契约版本：`1.0`。状态：目标契约，服务端尚未实现，不是现有 API 使用说明。

本文件可单独交给 `D:\03_projects\AI-tools\aiw-de\personal-secretary` 的开发者。它包含接入所需的边界、接口与验收要求，不要求读取另一份方案。知识库开发者也以本文件为跨项目契约唯一来源。变更须两边同步，不得各自猜测字段。

## 1. 目标与现状

用户通过知识库 WebUI 远程上传资料或创建笔记，通过秘书查询、归纳、引用资料并按明确要求保存笔记。知识库承载长期资料；秘书保留聊天、偏好、工作状态和授权的所有权。

秘书现有 `conversations.py` 使用词汇相关性最多选取四条记忆，摘要流程最多保存 200 条记忆。`assistant_tools.py` 提供网页和受控文本文件操作；`daily_assistant.py` 负责工具循环。这些能力保留，不迁移私库。

知识库已有 HTTP 文档与检索能力，但下面新增接口及鉴权、持久化幂等语义仍需实现。**本接入不使用 MCP，不启动知识库子进程，不直接打开知识库数据库或文件。**

## 2. 秘书修改范围

- 新增独立 `knowledge_client.py`：配置、HTTP 编解码、响应校验、超时、受控重试和错误映射。具体命名可服从项目惯例。
- 设置新增 enabled、baseUrl、credentialRef；凭证存入既有私密配置机制。默认为关闭，不写死端口。
- `assistant_tools.py` 增加知识搜索、读取、保存和保存状态查询的分发，保持文件授权机制不变。
- `daily_assistant.py` 增加工具说明、来源引用和预算处理，普通对话不必每次查询知识库。
- 本地持久化保存动作的 idempotencyKey、规范化请求体、状态和结果，用于重启或超时后的对账；不复用平台专业任务 outbox 协议。
- `conversations.py` 保留现有记忆，不全量导出聊天或自动双写。知识查询结果仅进入当前任务所需上下文。
- UI 增加连接状态、错误提示和来源链接。新增配置不能要求用户编辑工具 JSON。

## 3. 通用协议

部署基线为秘书与知识库不同主机。知识库由其主机独立管理，秘书不启动或停止知识库服务，不共享数据库和索引目录。

前缀：`{baseUrl}/api/v2/secretary`，baseUrl 只含协议、主机及可选端口，不带路径、query、fragment 或 userinfo。必须 HTTPS，校验证书及主机名，不允许跳过 TLS 校验。客户端不跟随重定向，避免凭证泄漏。

publicBaseUrl 必须是用户浏览器可达的 HTTPS 阅读入口，优先与 baseUrl 相同；允许 API 与浏览器入口不同，但二者须指向同一个知识库。禁止生成 localhost、容器名或文件路径形式的引用。用户应在设置中信任阅读 origin，不因服务端返回任意 origin 自动放宽链接限制。

浏览器认证已于 2026-10-30 确认采用 Caddy + Authelia；不影响本契约：秘书始终使用独立 Bearer 凭证，API 返回 JSON 401/403，不重定向浏览器登录页。秘书不存储浏览器密码、二次验证密钥或登录 cookie。

所有接口发送 `Authorization: Bearer <token>`；JSON 请求发送 `Content-Type: application/json`。响应 JSON 为 UTF-8。所有响应携带 `X-Request-Id`；它由服务端生成，用于诊断，不作为幂等键。禁止接受 workspaceId 或服务器文件路径参数。

成功统一封装：

```json
{"apiVersion":"1.0","requestId":"req_opaque","data":{}}
```

失败统一封装：

```json
{"apiVersion":"1.0","requestId":"req_opaque","error":{"code":"UNAVAILABLE","message":"知识服务暂不可用","retryable":true,"details":{}}}
```

客户端必须校验 apiVersion 和必需字段，忽略新增响应字段；服务端拒绝未知请求字段。破坏性变更使用新契约版本，不能静默改变 1.0。反向代理返回非 JSON 时，客户端显示受控连接错误，不向模型转发原始页面。

字符串长度按 Unicode 码点计，传输上限按 UTF-8 字节计。请求 JSON 最大 256 KiB，响应最大 1 MiB；服务端以预算和分页限制响应，不截断 JSON。时间统一 RFC3339 UTC。ID 是服务端返回的不透明字符串，客户端不得解释为路径或数据库主键。

WebUI 的单次上传文件大小另设为 100 MB（100,000,000 字节，一个文件或一个 ZIP 包），不适用于本秘书 API；不得因此将 JSON 请求或笔记正文限制扩大到 100 MB。本契约不新增秘书上传工具。

权限：`knowledge:read`（能力/搜索/读取），`knowledge:note:create`（创建/查询自己的创建记录）。scope 之外还必须检查凭证的资料集合授权范围；没有授权范围内的资源统一返回 404。首版可给个人秘书整个个人库的 read 权限，但不能由请求扩权。

## 4. 能力查询

`GET /capabilities`，需要 read，200：

```json
{"apiVersion":"1.0","requestId":"req_1","data":{"serviceId":"kb_opaque","capabilities":["search","read","create_note","creation_status"],"limits":{"maxSearchLimit":20,"maxExcerptChars":2000,"maxReadChars":20000,"maxNoteChars":100000},"publicBaseUrl":"https://knowledge.example.com"}}
```

serviceId 在同一知识库恢复或重启后保持稳定。秘书将保存记录绑定 serviceId；切换到其他库不能将旧创建请求自动重放。连接设置或启用时读取能力，不在每个工具调用前重复探测。缺少能力就禁用对应工具，不回退调用猜测的旧接口。

## 5. 搜索

`POST /search`，read，200。虽然使用 POST，但只读、可安全重试。

```json
{"query":"以前的 Windows 迁移笔记","limit":8,"excerptChars":1200,"filters":{"collectionIds":[],"tags":[],"formats":[],"updatedFrom":null,"updatedBefore":null}}
```

- query 必填，去空白后 1–2000 字符；limit 默认 8，范围 1–20；excerptChars 默认 1200，范围 200–2000。
- filters 可省略；数组默认空，表示不增加过滤。collectionIds 最多 20；tags 最多 20、每项 1–100 字符。
- formats 枚举：`markdown,text,html,pdf,docx,image,other`；同一数组内 OR，不同过滤项之间 AND。
- 时间为可选 RFC3339 UTC，updatedFrom 包含、updatedBefore 不包含；同时提供时前者必须早于后者。
- collectionIds 中任何未知或不可访问的值返回 404，不泄漏具体权限原因。

data 示例：

```json
{"items":[{"documentId":"doc_opaque","revision":"rev_opaque","contentHash":"sha256:hex","title":"电脑迁移记录","format":"html","collectionIds":["col_opaque"],"tags":["迁移"],"updatedAt":"2026-10-29T00:00:00Z","excerpt":"先备份配置……","location":{"kind":"section","label":"配置备份","page":null,"startLine":null,"endLine":null},"source":{"kind":"import","displayName":"OneNote/电脑迁移","url":"https://knowledge.example.com/webui/documents/doc_opaque"},"freshness":"current"}],"complete":true,"hasMore":false,"diagnostics":[]}
```

结果默认每个 documentId 一项，按服务端相关性排序；无命中为 items=[]、complete=true。hasMore 表示匹配超过 limit，本版不提供搜索翻页，可缩小查询。complete=false 表示有来源失败或无法完成，不等于正常结果数量限制。

freshness 为 current/stale/unknown。diagnostics 是 `{code,message}` 数组，例如 PARTIAL_INDEX、SOURCE_UNAVAILABLE、STALE_CONTENT；不得泄漏路径或凭证。部分成功返回 200；完全无法检索返回 503。

location.kind 为 document/page/section/lines；label 必填，其余字段不可用时 null。page 与行号均从 1 开始，行号末端包含。source.kind 为 import/note/external_reference；url 必须是知识库受保护阅读页，不能是 file://、任意外部 URL 或含凭证链接。该阅读路由是待实现目标，必须与 WebUI 一起交付。

## 6. 读取正文

`GET /documents/{documentId}?revision={revision}&maxChars=12000&cursor={cursor}`，read，200。

- revision 必填，从搜索结果获取；不是历史版本查询，当前版本不同返回 409 REVISION_CHANGED。
- maxChars 默认 12000，范围 1000–20000。cursor 首次省略，后续使用服务端值；cursor 与 ID/revision/调用身份绑定且不可伪造，过期返回 410 CURSOR_EXPIRED。
- cursor 不携带可被客户端修改的路径。无效 cursor 返回 400 INVALID_CURSOR。

data 包含搜索项中的身份、标题、format、collectionIds、tags、updatedAt、source、freshness，并增加：

```json
{"documentId":"doc_opaque","revision":"rev_opaque","contentHash":"sha256:hex","title":"电脑迁移记录","format":"html","collectionIds":[],"tags":[],"updatedAt":"2026-10-29T00:00:00Z","source":{"kind":"import","displayName":"电脑迁移记录","url":"https://knowledge.example.com/webui/documents/doc_opaque"},"freshness":"current","content":"先备份配置……","contentFormat":"text","location":{"kind":"document","label":"正文","page":null,"startLine":null,"endLine":null},"nextCursor":null,"truncated":false,"diagnostics":[]}
```

contentFormat 仅 text/markdown，不返回主动 HTML 或二进制；正文是提取内容。truncated=true 时 nextCursor 必须非空；false 时必须 null。资料尚未解析返回 409 CONTENT_NOT_READY；不支持提取返回 422 UNSUPPORTED_CONTENT。附件不会通过此接口自动下载或执行。

## 7. 创建笔记

`POST /notes`，note:create；必须发送 `Idempotency-Key: <UUID>`。此端点只创建，不修改已有笔记。

```json
{"title":"电脑迁移检查清单","content":"# 检查清单\n\n先备份配置。","format":"markdown","tags":["迁移"],"collectionId":null,"citations":[{"documentId":"doc_opaque","revision":"rev_opaque","location":{"kind":"section","label":"配置备份","page":null,"startLine":null,"endLine":null}}]}
```

- title 必填，去空白后 1–200 字符；content 必填，1–100000 字符且总请求不能超过 256 KiB；format 必须 markdown。
- tags 可省略，最多 20 项、每项 1–100 字符；collectionId 省略/null 表示默认笔记集合，必须具有该集合创建权限。
- citations 默认 []、最多 20 项。每项 ID/revision 必填，location 可省略，格式同搜索。引用版本不再可验证返回 409 CITATION_CHANGED，秘书重新读取后才可生成新的保存动作。
- 服务端验证引用对象可读，不接受客户端提供引用 URL；保留引用的版本、位置及保存时标题/来源快照，不把引用伪装为永远最新。
- 不传聊天原文、私库路径、凭证或完整模型上下文。创建来源由服务端记录为秘书服务身份。

首次成功受理返回 202，data 与创建状态查询一致：

```json
{"idempotencyKey":"uuid","operationId":"op_opaque","state":"pending","documentId":null,"revision":null,"url":null,"indexState":"pending","error":null}
```

操作 state 为 pending/succeeded/failed。成功后 documentId/revision/url 必填，失败时 error 为 `{code,message,retryable}`。indexState 为 pending/ready/failed/unsupported，区分保存与可搜索状态；保存成功但索引失败仍为 succeeded，indexState=failed。

只有原文、笔记元数据及创建结果均已持久化并可恢复，才能返回 succeeded。客户端此时可以说“笔记已保存”；只有 indexState=ready 才能说“已可搜索”。

### 幂等和未知结果

- 幂等作用域为稳定服务主体 + key；凭证轮换不得改变主体。记录持久化，首版不自动过期。
- 同键同规范化请求：返回同一 operation，pending 为 202，终态为 200；不得重复创建。
- 同键不同请求：409 IDEMPOTENCY_CONFLICT。规范化使用经过默认值补齐和字段校验后的 JSON 对象；对象键顺序无关，数组顺序及正文空白有意义。
- 受理记录必须在返回 202 前持久化；崩溃恢复不得产生重复笔记。文件、数据库和索引跨边界失败需要可恢复状态，不能仅做内存去重。
- 验证/鉴权失败不创建操作记录。已受理后的失败保留终态，不会因同键重发重新执行；新一次明确重试保存才生成新键。
- 删除已创建笔记后仍保留幂等结果/墓碑，重放不能恢复被删除笔记。读取结果链接可能返回 404。

## 8. 按幂等键对账

`GET /note-creations/{idempotencyKey}`，note:create，仅允许读取当前服务主体自己的记录。存在返回 200，data 为第 7 节对象。不存在返回 404 CREATION_NOT_FOUND。

秘书发送前持久化 key 与请求；POST 超时或连接中断时先查此接口。404 只允许使用同一个 key、同一请求体重发，不能换键。查询本身失败时保留 unknown 状态，报告“保存结果尚未确认”。

轮询间隔建议 1、2、4 秒后最高 10 秒，尊重 Retry-After；前台等待最多 30 秒，之后保留记录供后台或用户查询。暂停/取消停止后续轮询；不能宣称已撤销已受理保存。首版没有取消服务端创建操作的 API。

## 9. 错误、预算与兼容

| HTTP | code | 客户端行为 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT / INVALID_CURSOR | 修正参数，不自动重试 |
| 401 | UNAUTHENTICATED | 提示配置或轮换凭证，不重试 |
| 403 | FORBIDDEN | 提示缺少操作 scope，不申请更大范围 |
| 404 | NOT_FOUND / CREATION_NOT_FOUND | 区分资料缺失与第 8 节对账规则 |
| 409 | REVISION_CHANGED / CITATION_CHANGED | 重新搜索/读取，不静默混用版本 |
| 409 | CONTENT_NOT_READY | 提示正在处理，可稍后查询 |
| 409 | IDEMPOTENCY_CONFLICT | 停止并报告本地保存请求不一致 |
| 410 | CURSOR_EXPIRED | 从相同版本第一页重新读取，仍受预算限制 |
| 413 | PAYLOAD_TOO_LARGE | 缩小内容，不静默截断保存正文 |
| 422 | UNSUPPORTED_CONTENT | 说明仅保存原件，正文不可读取 |
| 429 | RATE_LIMITED | 按 Retry-After 延迟，保持总体预算 |
| 500 | INTERNAL | 受控错误；创建请求先对账 |
| 503 | UNAVAILABLE | 降级对话；创建请求先对账 |
| 504 | DEADLINE_EXCEEDED | 读取可重试；创建请求先对账 |

服务端同步搜索/读取预算 10 秒；秘书连接超时 3 秒、单请求总超时 15 秒。只读调用最多自动重试一次，仅针对连接故障、429、503、504；写调用只按对账规则重发。不能把这些重试叠加成无限工具循环。

每次用户请求默认最多一次搜索、三次正文读取，累计进入模型的知识正文及片段最多 30000 字符；重复分页也计入。需要更多资料时由用户继续或明确扩大范围。保存正文预算独立，不因上下文截断默默丢失内容。

错误信息、日志和模型工具结果不得包含 token、服务器绝对路径、SQL、栈或代理响应全文。服务端资料正文是非可信背景，不得成为系统指令。

## 10. 回答与保存行为

- 个人资料问题优先查询知识库；最新外部信息仍走原网页工具，不将旧笔记当作实时事实。
- 引用使用服务端 title/url/位置，区分资料原文、秘书归纳与外部资料。
- complete=false、stale 或 unknown 必须在会影响答案时说明；无命中不等于查询失败。
- 用户明确要求保存才调用创建工具；工具执行成功前不能回答“已保存”。不默认把每次聊天自动入库。
- 仅打开配置的 publicBaseUrl 下的来源链接；不自动抓取搜索结果携带的任意外部地址。
- 首版不提供删除、覆盖原件、修改授权、上传服务器路径、查询任意数据库等秘书工具。

## 11. TODO 与 Verification

- [x] 固定 HTTP-only 接入、私库边界和目标 API 1.0。
- [ ] 秘书：配置与客户端、工具循环、引用显示、持久化对账。
- [ ] 知识库：实现新增路由、凭证/集合权限、公开阅读链接和持久化操作。
- [ ] 双方：使用同一组请求/响应 fixture 验证契约；新增响应字段兼容，未知请求字段拒绝。
- [ ] 联调：查到/查不到/部分结果、版本变化、长文分页、401/403、停服降级。
- [ ] 联调：POST 响应丢失、同键并发、进程重启、同键异体、索引失败及删除后重放。
- [ ] 真实验收：WebUI 新笔记或导入 HTML 在秘书中查到，答案带可打开引用；秘书保存的笔记能在 WebUI 阅读。

本文件只定义方案，未修改秘书代码，未运行服务、测试或网络联调。服务端尚未提供本契约前，秘书可实现客户端与受控 fixture 验证，但必须保持默认禁用，不能宣称端到端完成。

%% 待部署时填写 baseUrl/publicBaseUrl 和凭证分发方式；不得在文档中填入真实密钥。
