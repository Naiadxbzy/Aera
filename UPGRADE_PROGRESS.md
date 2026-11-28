# Aera（GenshinChat）升级进度

## 阶段 Checklist

- [x] 第一阶段：源码、构建、稳定性与竞品盘点，确定四项新增功能。
- [x] 第二阶段：成批修复稳定性、流式体验与构建隐患，集中验证 `assembleDebug`。
- [x] 第三阶段：成批完成四项功能，包括持久化与界面，集中验证 `assembleDebug`。
- [x] 第四阶段已完成关键测试、交付报告和改动索引。

API Key 与供应商凭据沿用现有存储方式。代码变更限定在 Android 应用及其构建、测试和账本。盘点时工作区已有独立的 Web 和文档修改。

## 现有架构盘点

| 层 | 实现与已覆盖功能 |
| --- | --- |
| Android 界面 | 单 `app` 模块，主要用 Java、传统 View 和 AppCompat。主界面是 ViewPager2 的历史页和聊天页，以 40 条为窗口渲染消息。已有主题、底部跟随、展开编辑器、错误详情、编辑、重试、版本切换和全局历史搜索。 |
| 生成与后台 | 前台服务按会话持有请求，支持多会话、后台生成、排队、过程检查点、网络恢复等待、普通后台增强和可选 Shizuku 豁免。 |
| 协议与工具 | OkHttp 对接 Chat Completions、Responses、Anthropic Messages、Gemini，保存思考、用量、来源及服务端工具记录。已有采样参数、模型级能力、Tavily、上下文裁剪、协议信息与停止能力。 |
| 数据 | SharedPreferences / org.json 管理供应商、模型、会话、分支及队列。SessionRepository 管理会话缓存和后台落盘。支持 ZIP 导入导出与附件快照。 |
| 附件与富文本 | SAF / 系统分享、图片和文件、PDF 流式发送、逐页兼容、可选 OCR、Office / 文本提取。Markwon 渲染 Markdown、代码、高亮、公式、折叠思考和静态 HTML/SVG。 |
| 构建和回归 | SDK 37、minSdk 24、Java 11、AGP 9.3.3、Gradle 9.5.0、daemon JDK 25。已有 JVM 及大量设备回归测试。Release 显式禁用优化，`app/src/main/keepRules/rules.keep` 是本项目规则入口。 |

## 深水区 Bug 清单

行号为改动前版本。第二阶段集中实现并验证。

| 路径 | 现象与触发条件 | 修复方向 |
| --- | --- | --- |
| [MainActivity.java:445](app/src/main/java/com/changyu/ochat/MainActivity.java#L445)、[SessionRepository.java:53](app/src/main/java/com/changyu/ochat/SessionRepository.java#L53) | 自动命名直接 commit 全量会话，已排队的仓库写入可以随后覆盖标题。生成结束前独立清理检查点，进程若恰好终止有丢失窗口。 | 仓库负责全部写入，回复落盘与清理检查点采用同一个编辑事务。 |
| [ChatGenerationService.java:71](app/src/main/java/com/changyu/ochat/ChatGenerationService.java#L71)、[ChatGenerationService.java:119](app/src/main/java/com/changyu/ochat/ChatGenerationService.java#L119) | 队列在确认服务启动前即弹出，缺配置时缺少可见反馈，切版本可把队列送进另一条历史。停止仅取消网络，已排队的附件任务仍可能占用工作线程。 | 队列绑定分支，成功接管后写入消息，可见失败信息。持有并取消准备任务，封存同一请求的文本和思考缓冲。 |
| [MainActivity.java:38](app/src/main/java/com/changyu/ochat/MainActivity.java#L38)、[MainActivity.java:634](app/src/main/java/com/changyu/ochat/MainActivity.java#L634) | 活动回复视图离页后仍被引用，或创建时强制渲染尾页。生成完成重建整页并丢失跟随状态。历史异步跳转可覆盖新页面。 | 生成视图绑定会话与分页，稳定保留旧页，最后一条就地切换，跳转与恢复按页面代次处理。 |
| [HistoryBrowser.java:40](app/src/main/java/com/changyu/ochat/HistoryBrowser.java#L40)、[MainActivity.java:534](app/src/main/java/com/changyu/ochat/MainActivity.java#L534) | 搜索工作线程读取主线程正在修改的 JSONObject。Unicode 小写变换可改变字符数并产生无效的高亮/摘录区间；同长度编辑可能不更新索引，旧会话操作可能重新插入已删除对象。 | 用稳定快照和原始字符串坐标匹配。对话操作以当前仓库 ID 解析。 |
| [ChatApiClient.java:29](app/src/main/java/com/changyu/ochat/ChatApiClient.java#L29)、[ResponseStream.java:9](app/src/main/java/com/changyu/ochat/ResponseStream.java#L9) | SSE 需要标准空行分隔，部分兼容网关不符合；截断事件、变体报错或超长块没有完整处理。Responses 每个事件重拼全部文本和思考，出现二次增长。 | 容忍兼容 SSE 帧，限制原始响应和可显示文本，保留部分回答和错误诊断。增量处理有序 Responses，在重排、终帧时用快照。 |
| [AttachmentSnapshots.java:79](app/src/main/java/com/changyu/ochat/AttachmentSnapshots.java#L79)、[ChatGenerationService.java:286](app/src/main/java/com/changyu/ochat/ChatGenerationService.java#L286)、[ConversationBackup.java:55](app/src/main/java/com/changyu/ochat/ConversationBackup.java#L55) | 大量历史图片与内联文档累计占用堆内存，快照按描述符长度读入，ZIP 每项的读入上限未扣除前面条目。 | 限制内联附件的堆内累计额度和快照读取，保留 PDF 磁盘流式路径，ZIP 按剩余空间读取。 |
| [FollowOutput.java:15](app/src/main/java/com/changyu/ochat/FollowOutput.java#L15)、[ScreenInsets.java:21](app/src/main/java/com/changyu/ochat/ScreenInsets.java#L21)、[MarkdownMessageView.java:16](app/src/main/java/com/changyu/ochat/MarkdownMessageView.java#L16) | 点击就解除跟随，IME 布局终态提前应用时产生跳动，思考和正文各自触发刷新。未闭合 Markdown 在逐 token 重解析时频繁变化。 | 按真实滚动手势脱离，每次生成合并正文和思考刷新，保持已排版内容并稳定未闭合文本，IME 按动画进度处理。 |
| [SelectableSpanTouch.java:13](app/src/main/java/com/changyu/ochat/SelectableSpanTouch.java#L13)、[ShizukuBackgroundController.java:55](app/src/main/java/com/changyu/ochat/ShizukuBackgroundController.java#L55) | 已有回归记录显示链接长按结束可能误打开。R8 当前关闭，Shizuku 用户服务依赖以类名跨进程实例化。 | 单次接管 Markdown 链接手势，保留选择。为 Shizuku 服务入口提供针对性 keep 规则并实际构建 Release。 |

## 竞品差距对比

核对日期为 2026-11-27。版本取官方 Latest，需求关注度取公开 GitHub API 的 👍 排序及评论数。功能选择同时依据已经发布的移动端工作流。

| 竞品 | 已核对体验 | Aera 的对应差距 |
| --- | --- | --- |
| [Kelivo 1.3.7](https://github.com/Chevey339/kelivo/releases/tag/v1.3.7) | 同一对话保留文字和附件草稿。会话内可查找，支持上下一处、大小写、全词及思考内容。[官方手册](https://kelivo.psycheas.top/guide) | Aera 草稿局限于 Activity，历史全局搜索每个会话仅定位首条匹配。 |
| [NextChat 2.16.1](https://github.com/ChatGPTNextWeb/NextChat/releases/tag/v2.16.1) | 内置和自定义提示词，后续演进为提示词模板。提示词需求 #138 有 53 个 👍 和 75 条评论，模板实现见合并的 #993。[需求 #138](https://github.com/ChatGPTNextWeb/NextChat/issues/138) · [模板 PR](https://github.com/ChatGPTNextWeb/NextChat/pull/993) | 已有单会话系统提示词与全局默认值，缺少独立管理、在输入框复用的模板。 |
| [Cherry Studio 2.2.3](https://github.com/CherryHQ/cherry-studio/releases/tag/v2.2.3) | 提示词按使用场景管理，选中消息可导出 Markdown。草稿 #843 有 4 条评论，导出 #20623 有 1 个 👍，导出时间戳 #13272 有 2 个 👍。[发布记录](https://github.com/CherryHQ/cherry-studio/blob/main/resources/cherry-studio/release-history.json) · [草稿 #843](https://github.com/CherryHQ/cherry-studio/issues/843) · [导出 #20623](https://github.com/CherryHQ/cherry-studio/issues/20623) · [时间戳 #13272](https://github.com/CherryHQ/cherry-studio/issues/13272) | Aera 的 ZIP 适合恢复，日常摘录只能逐条复制，缺少可读文件。 |
| [Chatbox 1.24.4](https://github.com/chatboxai/chatbox/releases/tag/v1.24.4) | 版本切换和重新生成时恢复阅读位置，移动端消息工具栏与中断状态常驻。 | Aera 已有消息工具栏、输出继续和跳底按钮，第二阶段补齐页面保留。 |
| [LobeHub 2.3.6](https://github.com/lobehub/lobehub/releases/tag/v2.3.6) | 会话分享与导出，主题管理和导出集成 #9642 有 4 个 👍 和 14 条评论。[需求 #9642](https://github.com/lobehub/lobehub/issues/9642) | 可以共用现有会话管理入口，加入当前版本的 Markdown 文档输出。 |
| [RikkaHub 3.3.1](https://github.com/rikkahub/rikkahub/releases/tag/3.3.1)、[ChatterUI 0.10.0](https://github.com/Vali-98/ChatterUI/releases/tag/v0.10.0) | 移动端自配置模型、思考和 Markdown 等体验与 Aera 有较多重合。ChatterUI 也支持本地模型运行。 | 本轮聚焦现有 BYOK 请求链路与阅读体验。 |

## 待落地新功能

1. **按对话保存草稿**。自动保存文字、光标和附件，切换对话与进程重建后恢复，历史显示草稿提示。提交成功后清理对应草稿。
2. **提示词库**。配置页管理可搜索的自定义模板，从输入区选择，填充变量后在光标处插入，沿用正常发送流程。
3. **会话内查找**。对当前版本在后台匹配，提供上一个和下一个位置、命中计数及定位高亮，可选择匹配思考内容。
4. **Markdown 导出**。对话入口导出当前版本，消息操作可单条导出。保留 Markdown 源文、角色、时间、模型及附件名称，Android 文件选择器保存。

## 当前断点

四个阶段均已完成。Debug、Release、Lint 和设备测试 APK 构建通过。182 项 JVM 用例全部通过。完整 Android 17 设备套件记录 202 项通过、0 失败，另有两项需显式启用并授权的 Shizuku 集成用例按默认配置跳过。

## 升级交付报告

### 稳定性与构建

- **生成服务**按实例跟踪实际接管的请求。旧实例退出时只收尾该实例接管的工作，并保留刚登记、正等待启动的新请求。设备测试用相邻主线程任务安排前一次结束和下一次启动，修复前复现了前台服务启动崩溃。
- **会话持久化**由仓库统一处理。最终回复与检查点清理同批提交，删除对话时清理关联检查点，异步生成的标题通过仓库提交。切换或删除对话后，回调按 ID 核对当前对象，避免旧回调写回已离开的页面或已删除的数据。
- **停止与排队**取消网络请求和附件准备任务，并封存当次正文与思考缓冲。排队项保留原消息版本，确认启动后才转成聊天消息，配置缺失时保留队列并提供反馈。
- **流式协议**能处理省略空行、相邻 JSON、跨读取边界分块及截断。限制事件大小和嵌套深度，对多供应商错误保留可诊断的提示及已接收的文本。Responses 对有序事件追加增量，在重排和结束时用最终快照校准。
- **大响应与附件**限制可显示文本、原始响应和内联附件总量。超过内联上限的旧 PDF 附件迁移到文件流路径，ZIP 导入按剩余额度限制读取。历史匹配通过独立快照读取，Unicode 高亮沿用源文本坐标。
- **Android 兼容性**在显示前完成编辑和确认对话框按钮的初始化与配色，为 Shizuku 跨进程服务入口补充针对性的保留规则。API Key 和供应商凭据沿用原有存储方式。

### 流式与阅读体验

- 正文和思考合用每次生成的刷新任务，按文本长度以 64、100 或 180 毫秒合并回调。渲染和检查点共享快照，生成结束时就地更新当前消息。
- 聊天保留当前分页和阅读位置。用户上滑后可继续读历史，使用跳底按钮重新跟随。横向切页和纵向滚动各自处理手势。IME 过渡按 Insets 动画更新，保留焦点附近的位置。
- 流式 Markdown 延续已排版的内容，稳定未闭合代码、数学公式与思考块的显示。长按链接保留文本选择行为，错误时可从消息操作重试。

### 四项新增功能

| 功能 | 入口与持久化 | 参考 |
| --- | --- | --- |
| 按对话保存草稿 | 切换对话或重建界面后恢复文字、选区和附件，历史列表显示草稿提示。成功发送或加入队列时清理当前对话草稿。 | [Kelivo 官方手册](https://kelivo.psycheas.top/guide) |
| 提示词库 | 在设置管理和搜索模板。在输入栏打开模板列表，填写 `{{变量名}}` 后插入当前选区。模板保存在本地。 | [NextChat 提示词模板](https://github.com/ChatGPTNextWeb/NextChat/pull/993) |
| 会话内查找 | 从聊天页右上菜单搜索当前消息版本，可跨页定位上一个与下一个结果，并显示计数。支持大小写、全词和思考内容，记住查找选项。 | [Kelivo 官方手册](https://kelivo.psycheas.top/guide) |
| Markdown 导出 | 聊天、历史或消息操作可通过系统文件选择器保存 UTF-8 `.md`。文件保留所选版本或单条消息的正文、角色、时间、模型、附件名称和可用来源链接。 | [Cherry Studio 消息导出](https://github.com/CherryHQ/cherry-studio/issues/20623)、[LobeHub 会话导出需求](https://github.com/lobehub/lobehub/issues/9642) |

### 验证与产物

- `assembleDebug`、`assembleRelease`、`lintDebug` 和 `assembleDebugAndroidTest` 均通过。
- `testDebugUnitTest` 验证 182 项，0 失败、0 错误、0 跳过。新增 8 项覆盖 SSE 的拆包、截断和深度限制，分支队列提升，草稿快照，模板变量，Unicode 查找，以及 Markdown 的版本选择与内部字段隔离。
- `connectedDebugAndroidTest` 在测试 XML 中记录 204 项，202 通过、0 失败、2 跳过。四项新增设备用例均通过，覆盖草稿重建恢复、提示词变量插入、跨页查找与 SAF 导出，以及前台服务连续启动。两项真 Shizuku 集成用例需显式启用并授权，默认套件按设计跳过。
- 设备验证在 Android 17 的 `Medium_Phone` 模拟器上运行。网络回归使用测试传输和本地夹具。
- 产物为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release.apk`。
- 结果文件位于 `app/build/test-results/testDebugUnitTest/`、`app/build/reports/lint-results-debug.html` 和 `app/build/reports/androidTests/connected/debug/`。

### 改动文件索引

| 目录 | 改动文件 |
| --- | --- |
| `app/src/main/java/com/changyu/ochat/` · 请求与解析 | `AnthropicToolRequest.java`、`ApiFailure.java`、`ChatApiClient.java`、`NativeToolState.java`、`ResponseStream.java`、`SearchSources.java`、`SseReader.java` |
| `app/src/main/java/com/changyu/ochat/` · 生成与数据 | `AttachmentSnapshots.java`、`ChatGenerationService.java`、`ConversationBackup.java`、`ConversationBranches.java`、`GenerationQueue.java`、`MessageEdits.java`、`SessionConfig.java`、`SessionRepository.java` |
| `app/src/main/java/com/changyu/ochat/` · 渲染与交互 | `EditorDialogs.java`、`FollowOutput.java`、`HistoryBrowser.java`、`MainActivity.java`、`MarkdownMessageView.java`、`MarkdownRenderer.java`、`PaletteDialogs.java`、`ReasoningView.java`、`ScreenInsets.java`、`SelectableSpanTouch.java`、`StreamingMarkdown.java` |
| `app/src/main/java/com/changyu/ochat/` · 新功能 | `ComposerDraft.java`、`ConversationFindBar.java`、`ConversationMarkdown.java`、`ConversationMatches.java`、`PromptLibrary.java`、`PromptLibraryUi.java`、`SettingsHomeActivity.java`、`TextSearch.java` |
| 配置与资源 | `app/src/main/keepRules/rules.keep`、`app/src/main/res/drawable/ic_prompt.xml` |
| `app/src/test/java/com/changyu/ochat/` | `UpgradeRegressionTest.java` |
| `app/src/androidTest/java/com/changyu/ochat/` | `AttachmentLayoutRegressionTest.java`、`ButtonContrastRegressionTest.java`、`EditorDialogThemeRegressionTest.java`、`FeatureUpdateRegressionTest.java`、`FinalFlowRegressionTest.java`、`MessageControlsRegressionTest.java`、`PagerBranchesUiRegressionTest.java`、`PdfServiceAttachmentRegressionTest.java`、`TopicFallbackRegressionTest.java`、`UpgradeWorkflowRegressionTest.java`、`WelcomeModeRegressionTest.java` |
| 项目根目录 | `UPGRADE_PROGRESS.md` |
