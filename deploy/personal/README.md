# 个人知识库远程认证配置

本目录是同机 Caddy/Authelia 与知识库后端的部署材料，不是已部署或实测证明。秘书运行在另一主机，通过 Caddy 的 HTTPS origin 访问。后端只能绑定 `127.0.0.1` 或 `::1`。此示例使用 IPv4，不能把后端端口暴露到公网或局域网。

## 配置

设置 `personal_knowledge_enabled=true`、`http_bind_host="127.0.0.1"`、`public_base_url="https://你的知识库域名"`，以及两个绝对文件路径：`secretary_credentials_file`、`trusted_proxy_secret_file`。

秘书凭证文件格式：

```json
{"credentials":[{"subject":"personal-secretary","tokenSha256":"替换为随机token的64位小写SHA256十六进制摘要","scopes":["knowledge:read","knowledge:note:create"],"collections":["default","notes"]}]}
```

以上占位值有意不能通过配置校验。token 应由密码学随机源生成至少 32 字节，原值只交给秘书私密配置；文件保存摘要。文件修改后下一请求重新加载，删除对应项即可撤销。轮换 token 时保留同一 subject，保持幂等空间；不要把不同用户配置成同一主体。集合通配符可表示整个个人库，但只能由管理员配置，不能来自请求。

代理密钥文件只包含 43–128 字符的随机 base64url 文本；同一值注入 Caddy 的 `KB_PROXY_SECRET`。代理密钥与秘书 token 必须不同。两个文件仅服务账号与管理员可读，禁止放入 Git、共享资料目录、模型上下文或访问日志。

`Caddyfile.example` 精确匹配秘书 API 方法和路径。其他路径必须通过 Authelia；外来身份头先删除，认证成功后才复制 Remote-User。应用还检查 loopback 来源和独立代理密钥，单独伪造 Remote-User 无效。浏览器写 API 只接受 JSON、同 origin 和 `X-KB-CSRF: 1`。新上传端点使用独立有界流处理，不能复用旧 JSON 读取器。

## Authelia 账号与恢复

首版选单用户文件账号库、Argon2 密码摘要和 TOTP 二次验证，访问策略默认 deny，仅知识库域名允许指定用户且要求 two_factor。禁止 bypass 规则。会话 cookie 设为 Secure/HttpOnly，限定登录域和知识库域所需的域范围，不放宽到不可信子域。

Authelia 的版本专属完整配置应按选定安装版本生成并验证；此 FD 不自动安装、运行或替换已存在的 Authelia。账号库、会话存储、TOTP 注册数据和存储加密密钥一并纳入加密备份；邮箱恢复通道由操作者实际配置。丢失二次验证设备时，通过主机管理权限按该版本官方管理流程重置注册，再重新绑定；不为知识库提供公开二次验证绕过入口。备份恢复前停止服务，恢复后撤销旧会话并核对账号与数据。

## 上线前必须验证（尚未执行）

- 外部直连后端端口失败；不能靠登录页存在推断隔离成功。
- 未登录访问 WebUI、阅读页、附件和旧 API 均无法取得数据。
- 伪造 Remote-User、X-KB-Proxy-Secret 或只提供 Authorization 头不能绕过浏览器认证。
- 精确秘书端点缺 token 返回 JSON 401，不跳转登录；错误 scope 为 403。
- Authelia 停止后浏览器请求失败关闭；已授权秘书服务调用不依赖登录 cookie。
- 跨 origin 或缺少 CSRF 标记的写请求拒绝。
- 上传边界、慢连接、并发和原件恢复由后续完整实现的运行验收覆盖。

官方说明：[Caddy forward_auth](https://caddyserver.com/docs/caddyfile/directives/forward_auth)、[Authelia proxy authorization](https://www.authelia.com/reference/guides/proxy-authorization/)。

## 提供的 Authelia 模板

使用 authelia.configuration.yml.example 和 authelia.users_database.yml.example 作为现代 v4 配置起点，替换域名、主机路径、SMTP 和密码摘要；环境变量引用独立 session/storage/SMTP 密钥文件。默认禁用在线密码重置，二次验证恢复由主机管理员处理。模板未运行配置校验或真实代理验收，安装前需按目标版本官方说明校验。

账号与会话配置依据：[文件账号库](https://www.authelia.com/configuration/first-factor/file/)、[会话配置](https://www.authelia.com/configuration/session/introduction/)、[SMTP](https://www.authelia.com/configuration/notifications/smtp/)。
