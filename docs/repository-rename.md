# Aera 仓库与工程改名

## GitHub 仓库

1. 打开现有仓库，进入 **Settings → General**。
2. 在 **Repository name** 填写 `Aera`，点击 **Rename**。若提示名称已被使用，请先处理同名仓库。
3. 新地址为 `https://github.com/fogejoyog186-cpu/Aera`。仓库首页右侧 **About** 中的描述可更新为「A lightweight, clean Android AI chat client. Bring your own API key.」。已发布版本的标题与附件名称如需统一，请在对应 Release 页面编辑。
4. 在 Android Studio 的 Terminal 中更新本地远程地址：

```sh
git remote set-url origin https://github.com/fogejoyog186-cpu/Aera.git
git remote -v
```

也可以使用 **Git → Manage Remotes** 编辑 `origin`。确认 fetch 与 push 地址均为新地址，再正常 commit / push，无需强制推送。

GitHub 会为旧仓库地址保留重定向，已有 Stars、Issues 等信息继续保留。不要重新占用旧仓库名称，否则旧地址的重定向将失效。

官方说明：[Renaming a repository](https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository)。

## 本地工程与安装兼容

Gradle 工程名、应用显示名称、主题名称、图标资源名称、备份导出文件名、发布 APK 文件名和文档使用 Aera。当前版本仍为 1.2.0，versionCode 为 4，签名归档命令为：

```sh
./gradlew :app:archiveReleaseApk
```

Windows 使用 `.\gradlew.bat :app:archiveReleaseApk`。生成的签名 APK 为 `release-artifacts/Aera-1.2.0.apk`。

应用 ID / Java 包名保留 `com.changyu.ochat`，沿用已有发布密钥，确保能够覆盖安装并保留本地数据。已有备份格式、附件快照标记与 Shizuku 授权身份继续兼容；它们不是界面品牌名称。本机旧 APK 属于历史归档，不修改其签名内容，也不上传到源码仓库。

磁盘上的工程目录名称与 GitHub 仓库名称相互独立。若也要将本机目录改成 `Aera`，先关闭 Android Studio，再在资源管理器中重命名工程文件夹，最后重新打开并同步。无需更改应用 ID，也无需重新创建签名。

## 本次验证（2026-11-15）

- Gradle 同步、Debug、AndroidTest 编译、签名 Release 归档与 Lint 成功。
- JVM 单元测试：173 项通过，0 失败、0 跳过。此次改名未重新运行设备测试。
- APK 元数据确认所有语言的应用名称为 Aera，应用 ID、1.2.0 / versionCode 4 保持不变。
- 发布签名 v2 验证通过，证书与旧版相同；签名包为 6,445,902 字节。
- APK SHA-256：`bb2136ecdcc1bb9e656e1b22fa961ea1e9d11b38babeee794fe67d1e76edff24`。
- GitHub 仓库与本地 remote 尚未改动，按上面的步骤操作即可。
