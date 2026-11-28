# Aera

Aera 是一款 Android 端大模型对话客户端，支持自定义模型供应商、服务地址与 API Key。

## 功能概览

### 模型与协议

- 原生支持 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 与 Google Gemini 四类接口协议。
- 支持在线获取模型目录或手动录入模型 ID，支持按模型配置上下文长度、最大输出 Token、温度、Top P 与单价。
- 按模型能力提供思考强度档位选择，折叠展示接口返回的可读思考内容。
- 支持单会话独立覆盖模型、系统提示词、采样参数与联网设置，新建对话默认沿用上次所选模型。
- 支持配置独立的话题命名模型自动生成对话标题。

### 对话与效率工具

- 按对话自动保存未发送文本、光标选区与待发附件，切换会话或重建界面后自动恢复，并在历史列表中标记草稿状态。
- 内置提示词库，支持在设置页与输入栏新建、编辑、搜索提示词模板，插入时可填写 `{{变量名}}` 占位符并写入当前光标选区。
- 支持当前对话分支内关键词查找，提供命中计数、高亮定位、跨分页上下跳转，以及区分大小写、全词匹配和思考内容匹配选项。
- 支持全局历史全文检索、重命名、置顶、归档、批量整理与长对话分页浏览。
- 支持消息编辑、重新生成与多分支版本切换。
- 支持将当前对话分支或单条消息导出为 UTF-8 `.md` 文件，保留角色、时间、模型名称、附件名称与参考来源链接。
- 支持包含完整对话分支与可用附件快照的 ZIP 备份导出与导入恢复。

### 富文本、工具与后台运行

- 支持流式回复排版、LaTeX 行内与块级数学公式、语法着色代码块，以及 HTML / SVG 静态预览。
- 流式输出期间支持上滑暂停自动跟随并保持阅读位置，点击底部按钮可恢复跟随。
- 支持供应商内置联网搜索与 Tavily 搜索，在回复下方展示来源链接，同时支持按协议启用服务端网页读取与代码执行。
- 支持多会话并行生成、当前会话消息排队、前台服务后台生成与可选 Shizuku 省电豁免，详情见[后台连接说明](docs/background-connection.md)。
- 支持浅色、深色与跟随系统外观，可自定义主题色、背景色、表面色、文字色、思考动画配色及新对话欢迎页。

## 文档与图片输入

- 支持通过系统文件选择器、相册或系统分享导入图片、PDF、DOCX、XLSX、PPTX 与纯文本文件。系统分享导入的内容直接进入当前对话输入区并保留已有草稿，点击发送后正式提交或入队。
- PDF 默认以原文件流式编码发送，支持客户端内翻页预览与快照留存。当上游接口拒绝原文件且尚未返回回复时，客户端自动将页面渲染为图像兼容重试一次。连接 DeepSeek 官方接口时，`deepseek-flash` 及同系视觉模型直接发送页面图像。
- 模型详情页提供独立的「PDF 转文字」与「图片转文字」开关，可分别指定视觉识别模型。启用后先由视觉模型提取文本并按附件内容与模型标识缓存结果，再将识别文本提交给当前对话模型。
- DOCX、XLSX、PPTX 与纯文本文件在本地流式提取正文文本，单文件提取上限为 200,000 字符。

## 快速开始

1. 打开「设置 → 模型与供应商」，新建模型供应商。
2. 选择接口协议，填写服务地址与 API Key。
3. 在线获取模型列表或手动录入模型 ID，保存供应商配置。
4. 返回对话页选择模型，开始对话。

## 构建与测试

工程最低支持 Android 7.0（API 24），编译与目标平台为 Android SDK 37，使用 Android Gradle Plugin 9.3.3 与 Gradle Wrapper 9.5.0，Java 源码与字节码目标版本为 11，Gradle Daemon 运行环境为 JDK 25（见 `gradle/gradle-daemon-jvm.properties`）。通过 Android Studio 同步工程或在本机 `local.properties` 配置 Android SDK 路径后即可执行构建。

### Windows PowerShell

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

### macOS / Linux

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Debug 安装包输出至 `app/build/outputs/apk/debug/`，单元测试、设备回归测试与 Lint 报告输出至 `app/build/reports/`。运行 `connectedDebugAndroidTest` 前需连接 Android 设备或启动模拟器。

## 签名与发布归档

复制根目录下的 `keystore.properties.example` 为本机 `keystore.properties`，按 Java Properties 格式填写签名配置（Windows 路径使用 `/` 分隔符，字面反斜杠写作 `\\`）。

| 字段 | 说明 |
| --- | --- |
| `storeFile` | 密钥库文件的绝对路径或相对工程根目录路径 |
| `storePassword` | 密钥库密码 |
| `keyAlias` | 签名密钥别名 |
| `keyPassword` | 签名密钥密码 |

配置完成后执行归档任务生成签名安装包。

```powershell
.\gradlew.bat :app:archiveReleaseApk
```

macOS 与 Linux 环境执行 `./gradlew :app:archiveReleaseApk`。标准构建产物位于 `app/build/outputs/apk/release/`，按版本号命名的签名安装包输出至 `release-artifacts/Aera-<versionName>.apk`。覆盖升级时沿用应用 ID `com.changyu.ochat` 与同一签名密钥，并递增 `versionCode`。

## 工程结构与仓库

| 路径 | 说明 |
| --- | --- |
| `app/src/main/` | Android 应用源码、AIDL 接口、保留规则与界面资源（包名与应用 ID 为 `com.changyu.ochat`） |
| `app/src/test/` | JVM 本地单元测试 |
| `app/src/androidTest/` | Android 设备与模拟器回归测试 |
| `docs/` | 功能说明、版本发布说明与历次工程验证记录 |
| `.github/workflows/android.yml` | GitHub Actions 持续集成配置，自动执行 Debug 构建、单元测试与 Lint 检查 |

提交问题反馈时请附上应用版本、Android 系统版本、接口协议、模型 ID、复现步骤与脱敏后的错误详情。源码仓库、安装包发布页与远程地址说明分别见 [GitHub 仓库](https://github.com/Naiadxbzy/Aera)、[GitHub Releases](https://github.com/Naiadxbzy/Aera/releases) 与[仓库地址与包名兼容说明](docs/repository-rename.md)。
