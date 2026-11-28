# Aera 项目交接

更新时间：2026-11-21。这是一份新对话的起点。开始工作时先看当前分支、工作区和相关代码，版本与发布情况以当时查询结果为准。

## 当前状态

- Aera 是轻量的 Android AI 聊天客户端。用户自行配置模型提供商和 API Key，支持流式回复、会话历史、图片文档附件、联网搜索和后台生成。
- GitHub 仓库为 [Naiadxbzy/Aera](https://github.com/Naiadxbzy/Aera)，远程 `origin` 为 `https://github.com/Naiadxbzy/Aera.git`。本地目录仍叫 `GenshinChat`，这是历史目录名。
- 用户看到的应用名和 Gradle 工程名均为 Aera。Android 应用 ID 与 Java 包名仍是 `com.changyu.ochat`，用于保留已有安装、对话、设置和附件的升级兼容性。旧备份和附件格式标识也需要兼容。
- [app/build.gradle.kts](../app/build.gradle.kts) 中为 `versionName = "1.2.1"`、`versionCode = 5`。最低 Android 7.0（API 24），编译与目标 SDK 为 37。主应用以 Java 和原生 View 为主。
- 2026-11-21 核对时，本地 `main`、远程 `main` 和 `v1.2.1` 都指向 `7d7781a`（`chore(release): bump Aera to 1.2.1`）。`v1.2.0` 指向品牌改名提交 `df3486e`。
- [v1.2.1 已正式发布](https://github.com/Naiadxbzy/Aera/releases/tag/v1.2.1)。远程附件名为 `app-release.apk`，与本地 `release-artifacts/Aera-1.2.1.apk` 的 SHA-256 一致，均为 `0de568ad617fa6660edf4fd6f9a0229872917f0d96173bc04cc6ccf5b052cc44`。因此这版无需重新发布。

## 最近改动

`f74d19a` 修复了 Responses 的错误分类与聊天状态显示。

- 搜索开关关闭时，单独启用的代码执行仍可能向 Responses 请求添加 `code_interpreter`。以前只要有内置工具，HTTP 502 等错误也会被误标为内置工具失败。现在按 HTTP 状态与实际错误字段分类，诊断中保留请求 ID 和已发送的工具名称。
- 错误在聊天中展示为简短卡片，详情点开查看。带内置工具的 Responses 请求失败时，符合条件则提供「跳过内置工具重试」。重试保留原问题与模型，跳过的配置仅用于本次请求。
- 回复进度与搜索状态分别显示。两者使用带图标的紧凑圆角卡片。搜索有来源时可展开查看。
- 主要入口在 `app/src/main/java/com/changyu/ochat/` 下的 `MainActivity.java`、`ChatGenerationService.java`、`ChatApiClient.java`、`ApiFailure.java`、`BuiltinTools.java`、`SearchOptions.java` 和 `MessageStatusView.java`。
- 该修复通过了 174 项 JVM 单元测试、12 项模拟器回归与 Lint（0 错误）。使用本地模拟接口验证了 502、实际请求字段和手动重试。真实供应商的 502 原因未经验证。1.2.1 另完成了发布构建、APK 版本与签名验证。

## 日常开发和发版

- 开发和验证优先使用 Android Studio 工具，先检查 `git status --short --branch`，保留用户已有改动。
- 修改完成后，用中文说明结果和验证情况，并附上可直接复制的 **Commit message、Release 标题、发布说明**。版本已确定时附上 Release 标签。Commit message 默认用简洁的英文 Conventional Commits 格式。
- 发新版时一起调整 `versionName` 和 `versionCode`。覆盖升级继续使用相同的应用 ID 与发布签名。真实签名配置 `keystore.properties` 只留在本机。
- Commit、Push、创建标签和发布按当次任务的授权执行。发版前核对目标提交、标签与 APK 版本，避免错用旧包。
- Windows 常用命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:archiveReleaseApk
```

签名归档输出为 `release-artifacts/Aera-<versionName>.apk`，归档目录已被 Git 忽略。

更多历史见 [README](../README.md)、[后台连接](background-connection.md)、[SVG 修复](svg-display-2026-11-15.md) 和 [发布记录](releases/)。若历史文档中有旧仓库地址，以本文件中的 `Naiadxbzy/Aera` 为准。

## 新对话怎么开始

可以直接发送：

> 这是 Aera 安卓项目。先读 `docs/HANDOFF.md`，再核对当前 Git 状态。接下来请做：〔填你要做的事〕。完成后请附上 Commit message、Release 标题和发布说明。

如果还没定下一项，最后一句任务可以改成「先告诉我目前做到哪、有没有未完成的事」。清空上下文时记得保留这份文件；在同一个本地工程里可以直接读取它，换电脑或重新克隆时需要把它随项目带过去。
