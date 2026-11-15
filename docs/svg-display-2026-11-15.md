# SVG 显示与代码卡片修正（2026-11-15）

## 问题与原因

模型直接输出未带 Markdown 代码围栏的 SVG 时，消息视图只识别围栏代码块；原始 SVG 进入普通 HTML 清理流程，标签和路径被移除，最终只显示 title、desc 等文字。历史消息重新载入时也会走相同路径。

代码卡片和预览中的源码页此前写死深色背景与字体颜色，导致浅色主题仍显示深色区域。

## 实现

- 在普通文本清理之前识别独立 SVG / HTML 文档；有无围栏、XML 声明、DOCTYPE，以及未完成的流式片段均保留原始源码。
- 使用有边界的词法扫描处理嵌套 SVG、带引号的属性、注释和 CDATA，不解析 XML 外部实体；普通行内代码示例不会被误当图形。
- 消息、历史记录和思考内容复用同一代码卡片；XML / 通用代码标签中的 SVG 自动获得 SVG 预览入口。
- 统一浅深主题及自定义表面的代码配色。浅色模式采用浅灰底、细边框、分隔线、小号等宽字体，标签、属性、字符串和数字使用不同颜色，文字对比度至少 4.5:1。
- 复制保留原始标签、缩进和属性，不把标题、描述当作源码替代。流式更新保留已有代码视图、展开状态和滚动位置。
- 折叠状态保留代码区域直接双向拖动与滚动条；展开完整代码后移除嵌套滚动容器，由页面承接上下滑动。短代码不额外显示无用的展开行。
- SVG 图形保持作者原始配色，未将浅色主题需求转换为重写 SVG 的 fill / stroke。预览仍使用原有 3/4 屏面板，JavaScript 与本地文件访问保持关闭，网络图片、样式和 SVG 内部引用保留。

未新增依赖，版本保持 1.2.0 / versionCode 4。

## 验证

- Debug、AndroidTest、签名 Release 与归档构建成功。
- JVM 全量 173 项测试通过，0 失败、0 跳过；新增 11 项原始 SVG / HTML 边界与流式保真回归。
- Medium_Phone / Android 17（API 37），通过 AndroidJUnitRunner 实际运行 13 项设备回归：SVG 绘制像素、原始与 XML 代码卡片、主题与自定义背景、复制、历史恢复、页面重建、面板尺寸、双向拖动、完整展开及长 Markdown。
- 真实 SVG 画面检查了指定蓝色圆形的像素，确认不仅显示标题或描述。源码复制在前台 Activity 中验证，以符合 Android 剪贴板访问规则。
- 实际截图已检查，位于 app/build/svg-review/：svg-card-light.png、svg-canvas.png、svg-source-light.png。
- Lint：0 错误、58 警告；git diff --check 通过。
- 验证时的 1.2.0 归档 APK 与 app/build/outputs/apk/release/app-release.apk 的 SHA-256 相同：
  794a506bb16ccad1f5fccbe00740b04f2e0c3a208e90b244971dbcb8acc90a12。
- APK Signature Scheme v2 验证通过，沿用原发布证书。
