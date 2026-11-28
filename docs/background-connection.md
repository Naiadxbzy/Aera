# 后台连接说明

「设置 → 后台连接」用于管理对话生成期间的网络等待、设备唤醒与系统省电配置，包含默认开启的基础后台增强与可选的 Shizuku 省电豁免。

## 基础后台增强

- 回复生成由独立前台服务执行，切换会话或离开聊天页后继续接收流式响应。
- 生成请求与前置视觉识别请求的单次流式读取静默超时设为 10 分钟，每次收到响应数据后重新计时，点击停止按钮可立即中断当前请求。
- 存在活动生成任务时持有带超时的非计数唤醒锁并定期续期，全部任务结束后立即释放。
- 监听系统默认网络状态，尚未发出的前置步骤在断网时最多等待 10 分钟，网络恢复后自动继续执行。已发出的请求中断时保存已收到的正文与思考内容，暂停后续排队消息并记录错误详情。
- 设置页读取当前设备的电池优化、后台受限与通知权限状态，提供系统电池优化列表、应用详情页与通知设置入口。
- 开关调整对后续新发起的请求生效。关闭基础增强后，新请求采用 120 秒单次读取静默超时与 30 分钟单次运行上限。

## Shizuku 省电豁免

- 设备安装并启动 Shizuku（v13 及更新版本）后，可在后台连接页点击「授权并开启省电豁免」，为应用包名 `com.changyu.ochat` 添加系统 Doze 白名单。
- 权限授权由 Shizuku 处理，白名单查询、添加与移除通过短时非守护 UserService 执行，并在操作前后回读系统 `deviceidle whitelist` 状态进行核验。
- 仅当白名单记录由 Aera 新增时显示撤销入口，点击撤销即可移除对应豁免记录，退出设置页时自动释放服务绑定。

## 参考资料

- [Android Doze 与应用待机模式](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Android 网络状态读取指南](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
- [Android 前台服务类型与运行时限](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Shizuku 用户指南](https://shizuku.rikka.app/guide/setup/)
