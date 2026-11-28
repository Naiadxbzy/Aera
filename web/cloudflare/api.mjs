import { protocols } from '../public/capabilities.mjs';
import { publicHttps } from '../public/hosted-url.mjs';
import { hideSecrets, uuid } from '../generation-core.mjs';
import { providerModels } from '../protocols.mjs';

const jobPath = /^\/api\/jobs\/([0-9a-f-]+)$/;

export function response(value, status = 200) {
  return Response.json(value, { status, headers: {
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  } });
}

export function validateProvider(provider) {
  if (!provider || typeof provider.api !== 'string' || provider.api.length > 8192 ||
      !Object.hasOwn(protocols, provider.protocol) ||
      (provider.key !== undefined && (typeof provider.key !== 'string' || provider.key.length > 8192 || /[\r\n]/.test(provider.key)))) {
    throw new Error('请检查模型供应商和 API Key 设置。');
  }
  publicHttps(provider.api);
}

async function smallJson(request) {
  if (!request.body || !request.headers.get('Content-Type')?.startsWith('application/json')) throw new Error('请求格式必须为 JSON。');
  const reader = request.body.getReader();
  const bytes = new Uint8Array(20 * 1024);
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (size + value.byteLength > bytes.byteLength) {
        await reader.cancel();
        throw new Error('模型列表请求过大。');
      }
      bytes.set(value, size);
      size += value.byteLength;
    }
    return JSON.parse(new TextDecoder().decode(bytes.subarray(0, size)));
  } finally { reader.releaseLock(); }
}

export async function pagesApi(request, env) {
  const url = new URL(request.url);
  const origin = request.headers.get('Origin');
  const owner = request.headers.get('X-Aera-Session');
  if ((origin && origin !== url.origin) || request.headers.get('Sec-Fetch-Site') === 'cross-site' || !owner || !uuid.test(owner)) {
    return response({ error: '此接口仅接受当前站点的浏览器会话。' }, 403);
  }
  if (url.pathname === '/api/models' && request.method === 'POST') {
    let provider;
    try {
      provider = await smallJson(request);
      validateProvider(provider);
      return response({ models: await providerModels(provider, AbortSignal.timeout(25_000)) });
    } catch (error) {
      return response({ error: hideSecrets(error.message, { provider }) }, 400);
    }
  }
  const match = url.pathname.match(jobPath);
  if (!match || !uuid.test(match[1]) || !['GET', 'POST', 'DELETE'].includes(request.method)) {
    return response({ error: '接口不存在。' }, 404);
  }
  if (!env.AERA_JOBS) return response({ error: 'Cloudflare Pages 缺少 AERA_JOBS 绑定。' }, 503);
  try {
    const job = env.AERA_JOBS.get(env.AERA_JOBS.idFromName(owner + ':' + match[1]));
    const forwarded = new URL('https://job.aera.invalid/');
    if (url.searchParams.get('discard') === '1') forwarded.search = '?discard=1';
    return await job.fetch(new Request(forwarded, request));
  } catch {
    return response({ error: '暂时无法访问云端生成服务，请检查绑定和 Cloudflare 配额。' }, 503);
  }
}
