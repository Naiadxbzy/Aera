# 后台连接增强

入口：设置 → 后台连接。普通增强默认开启，Shizuku 操作需用户主动确认和授权。随 Aera 1.2.0 发布。

## 普通增强

- 回复生成由现有前台服务持有，不依赖聊天页是否可见。
- 生成请求（含识别模型）的单次读取静默超时从 120 秒延长到 10 分钟；收到数据会重新计算静默时间，不是总生成时间限制。取消按钮仍立即取消客户端请求。
- 在有生成任务时维护带超时的非计数唤醒锁，定期续租；最后一个任务结束后释放，不在空闲时保活。
- 开启普通增强后取消应用原有的单次 30 分钟主动停止；系统前台服务运行时限仍适用。
- 监听应用默认网络的可用、断开、认证和受限状态。尚未发出的步骤最多等待网络恢复 10 分钟，恢复后仅执行一次。未通过系统联网验证的 VPN 不会被直接判为断网。
- 已发出的模型请求不在切网后自动重新提交；仍保持原连接等待结果。如果原连接断开，保存已收到的文字与思考内容，显示具体失败原因，用户决定是否重试。
- 生成失败后不自动推进排队消息，避免在失败上下文中继续发起计费请求；队列保留供用户处理。
- 设置页回读电池优化、后台限制、通知和省电状态，提供省电优化列表、应用信息及通知设置入口。厂商自启动和最近任务锁定需按设备设置。

普通增强开关对新请求生效。已有前台服务即使关闭增强仍可后台生成，使用原有 120 秒读取静默超时和 30 分钟单次运行上限。

## Shizuku 增强

安装并启动 Shizuku v13 或更新版本后，点击“授权并开启省电豁免”。未授权时由 Shizuku 显示权限请求。拒绝、不再询问、Binder 断开、绑定超时和系统拒绝都会显示结果，不循环弹窗。应用返回设置页时重新检查真实状态。

特权操作通过短时、非守护模式的 UserService 完成。只允许固定目标 `com.changyu.ochat` 的 `dumpsys deviceidle whitelist` 查询、新增及移除，没有任意命令、包名、文件路径参数，不接收聊天内容或 API Key。Binder 根据实际调用 UID 限制为创建该服务的 Aera UID；Shizuku Provider 使用官方要求的系统权限保护。

先查询现有豁免，再操作，之后回读验证。原本已存在的豁免不会标记为本应用新增。只有由 Aera 新增的豁免才出现撤销入口；该本机记录不参与系统云备份和设备迁移。退出设置页会释放绑定，已经确认并开始执行的操作会完成状态记录后释放。

撤销只影响本应用新增的省电豁免，不撤回 Shizuku 本身的授权；授权可在 Shizuku 中管理。Shizuku 关闭或重启后状态会更新，不会在后台偷偷重启它。已经写入系统的省电豁免通常保留，页面以实际系统状态为准。

## 边界

Doze 豁免可减少系统休眠时的联网与 CPU 限制，不能解决信号消失、服务器断连、厂商杀进程或强制停止，也不会绕过 Android 的前台服务总时限。Android 15 及以上的 dataSync 前台服务受系统运行时间限制。没有通过修改全局休眠、强制启用移动数据、强制 Wi-Fi 锁或其他应用设置来保活。

## 验证（2026-11-14）

- Debug、AndroidTest APK、Release 构建成功；JVM 全量 162 项测试通过，0 失败、0 跳过。
- Lint：0 错误、58 警告；`git diff --check` 通过。
- Medium_Phone / Android 17（API 37）：后台生命周期、设置开关与取消、Shizuku 未启动、真实网络断开与恢复、取消待发请求，以及现有 PDF / 图片识别回归共 19 项通过。
- 官方 Shizuku 13.6.0，以 ADB 身份运行：实际添加、系统回读与撤销，执行中退出页面后的本机记录保存，共 2 项集成测试通过。另从设置页面实际完成授权弹窗与开启 / 撤销，并独立通过 `dumpsys deviceidle whitelist =com.changyu.ochat` 确认状态。
- Shizuku 集成测试默认跳过；只有模拟器、已授权且显式传入 instrumentation 参数 `shizukuIntegration=true` 才执行系统配置变更，并在结束后撤销本轮添加的豁免。
- 功能回归使用的预发布测试包为 1.1.1 / code 3，Release APK 已通过 APK Signature Scheme v2 验证，保持原发布证书。正式版本调整为 1.2.0 / code 4，发布说明见 [v1.2.0](releases/v1.2.0.md)。
- 验证设备为模拟器。厂商省电策略、实体设备长时间熄屏及 Root / Sui 环境未在本轮逐项实测，不能据此保证所有设备永不断线。

参考：

- [Android Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Android 网络状态监听](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
- [前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Shizuku 安装与启动指南](https://shizuku.rikka.app/guide/setup/)
