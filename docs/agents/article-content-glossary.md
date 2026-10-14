# Article 内容领域术语

本文档记录文章在线编辑、root 内容来源和版本管理使用的领域术语。它不定义 HTTP API 或数据库结构。

## Article（逻辑文章）

用户认知中的一篇知识内容，拥有稳定的 `articleId`。Article 可以由 root 文件、在线新建文件或上传文件承载。

Article 负责承载长期身份和 metadata，不等同于某一个物理文件路径。

## Source File（来源文件）

承载 Article 当前或历史正文的物理文本文件。主要来源包括已登记 root folder、`articles` 和 `uploads`。

## Root Folder（内容根目录）

用户指定并登记到 workspace 的主要内容目录。root folder 是知识库内容的主要事实来源，WebUI 可以直接写入其中的文本文件。

## Managed Article（托管文章）

由 WebUI 新建或通过上传产生、存放在 workspace `articles` 或 `uploads` 下的文章。其文件生命周期由 workspace 管理。

## Revision（文章版本）

Article 正文的一份不可变快照。每次正文成功保存都会创建新 revision；恢复历史版本也会创建新 revision，而不是修改旧 revision。

## Current Revision（当前版本）

Article 当前指向、并参与 Search/Ask 的 revision。历史 revision 和 trash 中的 revision 不参与正常检索。

## Metadata（文章元数据）

属于 Article 的 title、summary、keywords 和 tags。metadata 不属于某个正文 revision；正文版本变化时继承现有 metadata。

## Base Hash（编辑基线哈希）

用户打开文章时记录的正文哈希。保存时用于判断服务器端文件是否已经被本地编辑器或其他操作修改。

## Conflict（编辑冲突）

打开文章后，服务器端文件发生变化，导致 base hash 与当前文件 hash 不一致的情况。纯文本的非重叠修改可以尝试 patch merge；重叠修改必须交由用户处理。

## Patch Merge（补丁合并）

把用户基于旧版本的修改应用到服务器新版本的过程。第一阶段只允许纯文本的非重叠行级合并。

## Trash（回收站）

已删除文章及其版本的临时保留区域。删除 root 文件时，将原文件移动到 workspace 回收站；不在 root 中留下 tombstone。默认保留 30 天，之后清理。

## History（历史版本区）

workspace 管理的 revision 存储区域。History 不属于普通 root 内容，不应被 WebUI 当作可编辑文章，也不参与 Search/Ask。

## Index Projection（索引投影）

从 Article current revision 和 metadata 生成 chunks、embedding 及搜索索引的过程。文件保存成功和索引投影完成可以短暂处于不同状态。

## Detach（解除关联）

从 workspace 的 active root 列表中移除 root，但不执行内容删除。它不影响已经存在于 `articles` 或 `uploads` 中的托管文章。

## Purge（清理）

对 root 及其关联内容执行显式删除流程。文件进入 30 天回收站；如果部分文件不可访问或移动失败，允许部分成功并报告可重试状态。

## Article Identity（文章身份）

Article 的稳定逻辑身份。第一阶段主要使用 workspace 与规范化路径建立关联；内容 hash 用于 revision 和冲突检测，不单独作为文章身份。文件改名或移动暂按新文章处理。

%% 文件改名后的 Article 身份追踪尚未纳入第一阶段，后续若需要应单独形成架构决策。

