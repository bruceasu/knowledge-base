# 领域文档约定

探索代码库前，优先读取与当前主题相关的领域文档：

- 根目录的 `CONTEXT.md`；
- 如果存在则读取 `CONTEXT-MAP.md` 指向的相关 `CONTEXT.md`；
- `docs/adr/` 中涉及当前领域的架构决策。

如果这些文件不存在，继续工作，不要因为缺失而提前创建它们。

输出中的领域概念应使用领域文档定义的术语。若需要的概念尚未定义，应记录为领域建模缺口，而不是自行引入同义词。

如果实现或建议与已有 ADR 冲突，应明确指出冲突及重新审议的理由。

# Domain

Record project-specific domain terminology and conventions here.
