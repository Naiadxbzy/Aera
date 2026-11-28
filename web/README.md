# Aera Web

Aera Web 是独立的桌面网页端版本，通过本地 Node.js 进程转发模型与搜索请求，并在浏览器本地存储工作区数据，支持与 Android 端互通备份文件。

## 本地源码运行

环境要求为 Node.js 22.16 及以上版本。在 `web/` 目录执行以下命令启动本地服务。

```powershell
npm.cmd ci
npm.cmd start
```

macOS 与 Linux 环境执行 `npm ci` 与 `npm start`。默认监听地址为 `http://127.0.0.1:4173`，可通过环境变量 `AERA_PORT` 指定端口，同一访问地址与端口对应独立的浏览器工作区。

1. 打开「设置 → 模型供应商 → 新增供应商」，选择接口协议并填写服务地址与 API Key（本地免鉴权接口可留空 API Key，OpenAI 兼容接口通常包含 `/v1` 路径）。
2. 点击「获取模型列表」或手动添加模型 ID，保存供应商配置。
3. 在顶部选择目标模型开始对话，可在输入栏开启联网搜索并在设置中选择内置搜索或配置 Tavily。

## Windows 便携版打包

在 `web/` 目录执行以下命令生成包含 Windows Node.js 运行时与前端资源的便携目录 `../portable_web/`。

```powershell
npm.cmd ci
npm.cmd run package:portable
```

将 `portable_web` 目录整体复制至目标 Windows 设备，运行 `Aera-Web-Portable.bat` 即可启动本地服务并打开默认浏览器。默认优先使用 `4173` 端口，端口占用时依次尝试 `4174` 至 `4183`，也可通过环境变量 `AERA_PORT` 固定端口。关闭终端窗口即可停止服务。

## 功能概览

- 左侧栏支持会话全文搜索、重命名、置顶、归档与删除，编辑历史消息或重新生成回复时自动保留原有对话分支。
- 原生支持 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 与 Google Gemini 接口，支持流式输出、中断请求、多对话并行生成与单对话消息排队。
- 支持拖放导入文件、剪贴板粘贴图片、PDF 逐页预览及 DOCX、XLSX、PPTX 与纯文本提取。PDF 默认优先发送原文件，上游拒绝原文件时自动尝试逐页渲染图像重发，支持分别为 PDF 与图片配置视觉识别模型。
- 支持 Markdown 排版、代码语法着色、数学公式渲染与沙箱隔离的 HTML / SVG 静态预览。
- 「设置 → 数据与备份」支持导入与导出同 Aera Android 端一致的 `ochat-backup` ZIP 备份包（包含会话历史、多分支与可用附件）。
- 支持键盘快捷键 `Enter` 发送、`Shift + Enter` 换行、`Alt + N` 新建对话与 `/` 聚焦会话搜索。

## 数据存储与后台生成

- 本地服务绑定于 `127.0.0.1`，负责向已配置的模型接口与搜索接口发起请求，已提交的生成任务在切换会话、刷新页面或短暂关闭标签页后继续在本地进程中执行，已完成且待领取的结果默认保留约 1 小时。
- 会话历史与附件保存在当前浏览器的 IndexedDB 中，API Key 默认保存在当前标签页的 `sessionStorage`，开启记住凭据后写入当前站点的 `localStorage`。单附件大小上限为 64 MiB，单次提交至本地服务的 JSON 请求体上限为 96 MiB。

## Cloudflare Pages 部署

静态前端由 Cloudflare Pages 托管，同源 API 由 Pages Functions 转发，后台生成任务与待续接结果由 SQLite Durable Object（`aera-web-jobs` Worker）承载。

| Pages 配置项 | 配置值 |
| --- | --- |
| Framework preset | `None` |
| Root directory | `web` |
| Build command | `npm run build` |
| Build output directory | `dist` |
| 运行时绑定 | Pages Functions 与 `AERA_JOBS`（指向 `aera-web-jobs` 的 `GenerationJob`） |

部署时先在同一 Cloudflare 账号下通过 `web/cloudflare/wrangler.jsonc` 发布 `aera-web-jobs` Worker，再按上表创建并构建 Pages 项目，确保 `web/wrangler.jsonc` 或控制台中的 `AERA_JOBS` Durable Object 绑定生效。Cloudflare 部署模式适用于公网 HTTPS 模型接口，生成任务结束后自动清理暂存输入，浏览器确认接收后清理对应输出缓存。

相关平台配额说明见 [Cloudflare Pages 限制](https://developers.cloudflare.com/pages/platform/limits/)、[Cloudflare Workers 限制](https://developers.cloudflare.com/workers/platform/limits/) 与 [Durable Objects 计费说明](https://developers.cloudflare.com/durable-objects/platform/pricing/)。

### 本地验证与测试

在 `web/` 目录执行 `npm.cmd test`（macOS / Linux 执行 `npm test`）运行基于本地模拟接口的单元测试，执行 `npm.cmd run test:browser` 运行浏览器端布局、PDF、后台生成与备份回归测试（macOS / Linux 需先执行 `npx playwright install chromium`）。

如需在本地使用 `workerd` 验证 Cloudflare 运行时，可在 `web/` 目录依次执行以下命令。

```powershell
npm.cmd ci
npm.cmd run build
npm.cmd install --prefix .preview/cloud-tools --no-save --no-audit --no-fund wrangler@4.166.0
node .preview/cloud-tools/node_modules/wrangler/bin/wrangler.js pages functions build functions --project-directory . --outdir .preview/pages-bundle
node --test tests/cloud.browser.mjs
```

如需手动启动本地 Cloudflare 预览并在 `http://127.0.0.1:8788` 查看效果，可在两个终端分别执行以下命令。

```powershell
npx.cmd wrangler@4.166.0 dev --config cloudflare/wrangler.jsonc --local --port 8791
```

```powershell
npx.cmd wrangler@4.166.0 pages dev dist --port 8788 --do AERA_JOBS=GenerationJob@aera-web-jobs
```
