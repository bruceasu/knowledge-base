# 配置基线

新增配置由 `PersonalKnowledgeSettings` 校验，既有 CLI/TOML 覆盖规则不变。

| 键 | 默认/要求 |
| --- | --- |
| `personal_knowledge_enabled` | `false`；显式 `true` 才启用个人资料模块 |
| `http_bind_host` | `127.0.0.1`，只接受此值或 `::1` |
| `public_base_url` | 启用时必填，浏览器可达 HTTPS origin，不带路径、凭证、查询或 fragment |
| `secretary_credentials_file` | 启用时必填绝对路径，不在命令行传 token |
| `trusted_proxy_secret_file` | 启用时必填绝对路径，由应用与同机代理共享；不传给模型 |
| `personal_storage_quota_bytes` | 非负整数；0 表示没有逻辑总配额，不保证磁盘可用 |

生产 HTTP 入口现在默认仅监听 loopback。远程调用必须经过后续交付的可信代理配置；这是为异机安全部署有意收紧的监听行为。通用 `me.asu.http.HTTPServer` 未配置 bindHost 时保留既有行为。

本配置切片尚未接通个人资料业务模块，不能当作完整远程认证实现。稳定 serviceId 随 3.1 持久化表初始化，不能从随机启动值或域名派生。
