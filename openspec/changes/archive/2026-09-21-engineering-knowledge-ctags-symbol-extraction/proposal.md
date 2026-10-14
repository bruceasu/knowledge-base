## Why

纯文本命中无法可靠地区分类、接口、方法、字段和重载符号，因而不足以支撑工程上下文构建。将 Universal Ctags JSON 封装为受限、可诊断的符号提取 Adapter，可在不引入完整语义分析器的前提下提供带位置和来源证据的结构化代码候选。

## What Changes

- 新增 `CtagsSymbolExtractor`，通过一个受控请求/结果 seam 提取 Java 优先的类、接口、方法和字段等符号候选。
- 将 Universal Ctags JSON 规范化为稳定的符号身份、限定名称、签名（可用时）、工作区相对位置、语言、种类和静态分析来源证据。
- 规定工具版本/发现、受限工作区、支持字段、同名/重载处理、增量重跑等价性和不可用/异常输出诊断。
- 不做调用图、引用图、类型解析、Tree-sitter、LSP、LLM 推断或公共搜索路由；不修改 SQLite/Lucene/HTTP/CLI 和旧代码路径。

## Capabilities

### New Capabilities

- `ctags-symbol-extraction`: 从受限源代码工作区生成可追溯的结构化符号候选。

### Modified Capabilities

- 无。

## Impact

本变更预计新增本地外部工具 Adapter、符号结果值类型和 JSON 夹具测试。它依赖基础核心、SQLite Store 和工作区/进程边界的已确认约定；初期不新增第三方 Java 依赖，不持久化或公开符号结果，也不替换已有文本搜索或 Lucene BM25 行为。
