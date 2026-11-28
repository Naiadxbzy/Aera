# 仓库地址与包名兼容说明

## 远程仓库配置

若本地克隆仍指向旧远程地址，可在工程根目录执行以下命令将 `origin` 更新至当前 GitHub 仓库 [Naiadxbzy/Aera](https://github.com/Naiadxbzy/Aera)。

```sh
git remote set-url origin https://github.com/Naiadxbzy/Aera.git
git remote -v
```

也可通过 Android Studio 菜单 **Git → Manage Remotes** 将 `origin` 更新为 `https://github.com/Naiadxbzy/Aera.git`。确认抓取与推送地址一致后即可正常提交与推送。

## 安装与数据兼容

- Gradle 工程名、桌面应用名称、系统分享入口、主题资源、备份默认文件名前缀与发布归档 APK 文件名统一使用 `Aera`。
- Android `applicationId` 与 Java 包名沿用 `com.changyu.ochat`，发布版本使用同一签名密钥，支持在旧版本基础上直接覆盖升级并保留本地会话、供应商配置、附件快照及 Shizuku 授权记录。
- 本地工程文件夹名称与 Git 远程仓库名称相互独立。如需重命名本机工程目录，可先关闭 Android Studio，在文件管理器中修改目录名后重新打开并同步 Gradle。
