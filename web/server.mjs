import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { providerModels } from './protocols.mjs';
import { assets, csp } from './assets.mjs';
import { hideSecrets, initialSnapshot, jobRetention, requestLimit, runGeneration, uuid, validateInput } from './generation-core.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const mime = {
  '.css': 'text/css', '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.wasm': 'application/wasm',
  '.ftl': 'application/octet-stream', '.pfb': 'application/octet-stream', '.bcmap': 'application/octet-stream',
};
function send(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}

async function json(req) {
  if (!String(req.headers['content-type']).startsWith('application/json')) throw new Error('请求格式必须为 JSON。');
  if (Number(req.headers['content-length']) > requestLimit) throw new Error('请求超过 96 MiB，请减少附件后重试。');
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > requestLimit) throw new Error('请求超过 96 MiB，请减少附件后重试。');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks, total).toString('utf8'));
}

function staticPath(pathname) {
  if (assets[pathname]) return join(root, 'node_modules', assets[pathname]);
  const font = pathname.match(/^\/vendor\/fonts\/(KaTeX_[\w-]+\.(?:woff2?|ttf))$/);
  if (font) return join(root, 'node_modules/katex/dist/fonts', font[1]);
  const pdf = pathname.match(/^\/vendor\/(cmaps|standard_fonts|wasm)\/([\w.-]+\.(?:bcmap|pfb|ttf|wasm|mjs))$/);
  if (pdf && !pdf[2].startsWith('.')) return join(root, 'node_modules/pdfjs-dist', pdf[1], pdf[2]);
  if (pathname === '/') return join(root, 'public/index.html');
  if (/^\/[\w-]+\.(?:mjs|css|html)$/.test(pathname)) return join(root, 'public', pathname.slice(1));
  return null;
}

export function createAeraServer() {
  const jobs = new Map();
  const sweep = setInterval(() => {
    for (const [id, job] of jobs) if (job.finished && Date.now() - job.finished > jobRetention) jobs.delete(id);
  }, 60 * 1000);
  sweep.unref();
  const server = createServer(async (req, res) => {
    const address = server.address();
    const port = address?.port;
    const hosts = ['127.0.0.1:' + port, 'localhost:' + port];
    if (!hosts.includes(req.headers.host) || (req.headers.origin && req.headers.origin !== 'http://' + req.headers.host) ||
        req.headers['sec-fetch-site'] === 'cross-site') {
      return send(res, 403, { error: '此服务仅接受本机同源请求。' });
    }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', csp);
    const pathname = new URL(req.url, 'http://' + req.headers.host).pathname;
    try {
      if (pathname.startsWith('/api/')) {
        const owner = req.headers['x-aera-session'];
        if (typeof owner !== 'string' || !uuid.test(owner)) return send(res, 403, { error: '缺少浏览器会话标识。' });
        if (pathname === '/api/models' && req.method === 'POST') {
          const provider = await json(req);
          try {
            return send(res, 200, { models: await providerModels(provider, AbortSignal.timeout(25_000)) });
          } catch (error) {
            return send(res, 400, { error: hideSecrets(error.message === 'fetch failed' ? '无法连接供应商。' : error.message, { provider }) });
          }
        }
        const match = pathname.match(/^\/api\/jobs\/([0-9a-f-]+)$/);
        if (!match || !uuid.test(match[1])) return send(res, 404, { error: '接口不存在。' });
        const id = owner + ':' + match[1];
        const job = jobs.get(id);
        if (job && req.method === 'GET') return send(res, 200, job.snapshot);
        if (job && req.method === 'DELETE') {
          if (!job.finished) job.controller.abort();
          else if (new URL(req.url, 'http://' + req.headers.host).searchParams.get('discard') === '1') jobs.delete(id);
          return send(res, 200, { ok: true });
        }
        if (req.method !== 'POST') return send(res, 404, { error: '找不到这次生成，服务可能已重启。' });
        if (job) return send(res, 202, job.snapshot);
        const input = await json(req);
        validateInput(input);
        const existing = jobs.get(id);
        if (existing) return send(res, 202, existing.snapshot);
        const active = [...jobs.values()].filter(entry => !entry.finished).length;
        if (active >= 12) return send(res, 429, { error: '正在生成的对话过多，请等待其中一项完成。' });
        if (jobs.size >= 160) {
          const oldest = [...jobs].find(([, entry]) => entry.finished);
          if (oldest) jobs.delete(oldest[0]);
        }
        const snapshot = initialSnapshot();
        const fresh = { snapshot, controller: new AbortController(), finished: 0 };
        jobs.set(id, fresh);
        send(res, 202, snapshot);
        void runGeneration(fresh, input);
        return;
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, { error: '方法不可用。' });
      const file = staticPath(pathname);
      if (!file) return send(res, 404, { error: '页面不存在。' });
      const info = await stat(file).catch(() => null);
      if (!info?.isFile()) return send(res, 404, { error: '资源不存在。' });
      const type = mime[extname(file)] ?? 'application/octet-stream';
      res.writeHead(200, { 'Content-Type': type + (/^(text|application\/javascript)/.test(type) ? '; charset=utf-8' : ''), 'Content-Length': info.size });
      if (req.method === 'HEAD') res.end();
      else createReadStream(file).on('error', () => res.destroy()).pipe(res);
    } catch (error) {
      if (!res.headersSent) send(res, 400, { error: error instanceof SyntaxError ? '请求包含无效 JSON。' : error.message });
      else res.end();
    }
  });
  server.on('close', () => {
    clearInterval(sweep);
    for (const job of jobs.values()) job.controller.abort();
  });
  return server;
}

if (process.argv[1] && fileURLToPath(import.meta.url).toLowerCase() === process.argv[1].toLowerCase()) {
  const server = createAeraServer();
  const port = Number(process.env.AERA_PORT ?? 4173);
  server.listen(port, '127.0.0.1', () => console.log('Aera Web · http://127.0.0.1:' + server.address().port));
}
