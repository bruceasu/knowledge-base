# ADR：知识文章在线编辑与版本管理

- 状态：已接受
- 日期：2026-08-28
- 范围：workspace 内容来源、WebUI 在线编辑、文章版本、回收站与检索投影

## 背景

workspace 主要保存 SQLite、Lucene 索引和运行状态；知识库的主要内容来自用户指定的多个 root folder。`articles` 和 `uploads` 是在线创作和上传产生的补充内容，不能取代 root folder 作为主要内容来源。本地编辑器仍然是复杂创作的主要工具，WebUI 主要承担查看、快速修改、补充创作和索引维护。

Search 和 Ask 已经可以返回参考文章，但需要支持从参考结果进入文章查看页面，并在用户明确进入编辑模式后修改文本内容和 metadata。在线保存必须避免覆盖用户在本地编辑器中的并发修改，并保留可恢复的历史版本。

## 决策

### 1. 逻辑文章与物理文件分离

`Article` 是稳定的逻辑知识对象，拥有稳定的 `articleId`。物理文件是某个 revision 的内容载体。文章的 URL 和外部引用使用稳定的 `articleId`，不直接依赖当前文件路径。

一个 Article 的当前内容可以来自：

- 已登记 root folder 中的文件；
- `workspace/articles` 中的在线创作文件；
- `workspace/uploads` 中的上传文件。

### 2. root folder 仍是主要内容来源

对于 root folder 中的文本文件，WebUI 允许直接写回原文件。在线编辑不会默认把它复制成另一个 `articles` 文件，否则会产生两个事实来源。

`articles` 仅用于在线新建文章，`uploads` 仅用于浏览器上传内容。

### 3. 正文保存生成新 revision

正文每次成功保存都生成新的不可变 revision。旧 revision 不被覆盖，当前 Article 只指向一个 current revision。

恢复历史 revision 时，不直接把旧 revision 改成 current，而是复制历史内容生成一个新的 revision。这样恢复动作本身也保留在版本历史中。

metadata 属于 Article，而不是 revision。正文产生新 revision 时继承现有 metadata；单独修改 metadata 不产生正文 revision，但需要重新投影当前内容。

### 4. revision 统一由 workspace 管理

历史 revision 不散落在各个 root folder 中，统一放入 workspace 的系统管理区域，例如：

```text
workspace/.kb/history/<articleId>/<revisionId>.<ext>
workspace/.kb/trash/<articleId>/...
```

历史目录和回收站目录不是用户可通过 WebUI 编辑的文章来源，也不参与 Search/Ask。用户如果直接通过操作系统访问这些目录，属于系统外操作，不在 WebUI 保护范围内。

### 5. 冲突检测与合并

打开文章时记录 base hash。保存时比较服务器当前文件 hash：

- hash 未变化：生成新 revision 并保存；
- hash 已变化：对纯文本执行非重叠的行级 patch merge；
- merge 存在重叠冲突：拒绝保存并显示两个版本的差异，交由用户决定。

自动 merge 不适用于二进制文件，也不允许对重叠修改进行猜测性合并。

### 6. 删除与回收站

删除 root folder 中的文章时，不只删除索引记录。原始文件移动到 workspace 的回收站，避免下一次 sync 再次发现，同时保留恢复机会。删除不在 root 中留下 tombstone。

删除 `articles` 或 `uploads` 中的托管内容时，当前文件和历史 revision 一并进入回收站。回收站保留 30 天，之后自动清理；current revision 不会因为普通版本清理规则被删除。

恢复时恢复托管文章的 Article 和版本历史。若目标位置已经存在文件，不能静默覆盖，应进入冲突处理。

### 7. root purge 的失败语义

root purge 允许部分成功：已经移动到回收站的文件保持删除状态，无法访问或移动失败的文件保留原位置。系统需要报告部分失败状态并允许重试，不为了整体原子性阻塞已经成功的文件处理。

root 被 detach 或 purge 不影响已经位于 `articles` 或 `uploads` 中的托管 Article。root 来源文章则按照上述回收站规则处理。

### 8. 检索可见性

新建文章保存后立即成为 Search/Ask 的可检索内容。Search/Ask 只使用 Article 的 current revision，不检索 history 或 trash。

文件写入成功与索引投影完成是两个状态。允许出现“文件已保存、索引仍在更新”的短暂窗口，界面需要分别展示文件保存状态和索引状态。

## 后果

### 正面影响

- root folder 继续保持主要内容来源；
- WebUI 不会无意间制造同一文章的托管副本；
- 文章引用和 URL 在 revision 变化后仍然稳定；
- 本地编辑器和 WebUI 修改同一个 root 文件时可以检测冲突；
- 删除、恢复和历史查看都有明确路径；
- 历史文件不会被普通 sync 再次索引。

### 成本与风险

- 需要维护 Article、current revision 和物理路径之间的关系；
- 文件系统写入、数据库状态和 Lucene 投影不能组成单一原子事务；
- WebUI 对所有已登记 root 需要写权限；
- 删除 root 文件虽然可恢复，但仍属于高风险操作；
- 跨平台识别文件改名或移动不作为第一阶段能力，改名或移动默认按旧文章删除、新文章创建处理；
- 自动 merge 只覆盖纯文本且非重叠的修改。

## 未纳入本决策的范围

- 多人协作和分支版本；
- 复杂二进制文档在线编辑；
- 完整 CMS 权限与审批流；
- root 文件改名后的 Article 身份追踪；
- 自动 merge 的语义化合并；
- 是否支持网络 URL 的直接抓取；
- 具体编辑器产品选型。

## 待验证事项

- 回收站清理任务的运行时机和失败重试；
- root 文件移动到回收站时的权限和跨卷移动行为；
- 文件编码、换行符和文件权限的保留策略；
- root purge 部分失败状态的展示方式；
- 版本历史页面的查看、差异和恢复交互。

