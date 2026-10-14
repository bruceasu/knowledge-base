## ADDED Requirements

### Requirement: CLI 内容命令遵循服务优先边界

系统 SHALL 让 CLI 的同步、导入、检索、问答和重建命令通过已启动的 HTTP 服务完成内容操作，且测试 SHALL 验证该服务边界。

#### Scenario: CLI 同步并检索临时工作区

- **WHEN** 测试启动临时 HTTP 服务并执行 CLI 同步和检索命令
- **THEN** 命令通过该服务成功完成且返回预期结果

### Requirement: 配置发现兼容历史位置

系统 SHALL 继续加载规范配置位置，并兼容加载历史的 `$HOME/.config/knowlege-base.toml`；规范位置、环境变量和 CLI 参数 SHALL 按既有优先级覆盖历史位置。

#### Scenario: 仅存在历史配置文件

- **WHEN** 用户仅在历史配置位置保存配置
- **THEN** 系统加载其中的工作区和服务配置

### Requirement: 根路径临时重定向到 WebUI

系统 SHALL 将 `GET /` 以 302 临时重定向到 `/webui/index.html`。

#### Scenario: 浏览器访问根路径

- **WHEN** 客户端请求根路径
- **THEN** 响应状态为 302 且 Location 为 `/webui/index.html`

### Requirement: CLI 文本生成器测试跨平台稳定

系统 SHALL 使 CLI 文本生成器的测试不依赖 Windows shell 对空格参数的引号呈现。

#### Scenario: Windows 上运行文本生成器测试

- **WHEN** 测试以包含空格的提示调用测试命令
- **THEN** 断言文本内容而非 shell 额外引号，且测试通过
