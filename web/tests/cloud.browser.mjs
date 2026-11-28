import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from 'playwright';
import { hostedJobBody } from '../public/cloud-transport.mjs';

const tooling = createRequire(new URL('../.preview/cloud-tools/package.json', import.meta.url));
const { Miniflare, Response: MockResponse, convertV4MiniflareOptions } = tooling('miniflare');
const { build } = tooling('esbuild');
const compatibilityDate = '2026-11-21';
const providerHost = 'fixture.aera.example.org';

test('Pages assets, Function and external SQLite Durable Object work together locally', { timeout: 90_000 }, async t => {
  await build({
    entryPoints: ['cloudflare/worker.mjs'], outfile: '.preview/jobs-worker.mjs',
    bundle: true, format: 'esm', platform: 'neutral', external: ['cloudflare:workers'],
  });
  const captured = [];
  const sse = value => new TextEncoder().encode('data: ' + JSON.stringify(value) + '\n\n');
  const outboundService = async request => {
    const url = new URL(request.url);
    if (url.hostname === 'api.tavily.com') {
      const search = await request.json();
      assert.equal(search.api_key, 'tavily-fixture');
      assert.match(search.query, /^PDF/);
      return MockResponse.json({ results: [{ title: '资料', url: 'https://example.org/', content: 'Tavily 工具记录' }] });
    }
    assert.equal(url.hostname, providerHost);
    if (url.pathname === '/v1/models') return MockResponse.json({ data: [{ id: 'mock-model' }] });
    if (url.pathname === '/v1/redirect/models') return new MockResponse(null, {
      status: 302, headers: { Location: 'https://redirect.example.org/models' },
    });
    const body = await request.json();
    const rawPart = body.messages.at(-1).content;
    const marker = Array.isArray(rawPart) ? rawPart[0].text : rawPart;
    captured.push({ marker, length: Array.isArray(rawPart) ? rawPart[1]?.file?.file_data?.length : undefined,
      longText: Array.isArray(rawPart) && rawPart[0].text.length > 1000000, system: body.messages[0].content });
    if (marker === 'HTTP 502') return MockResponse.json({ error: { message: 'mock-key 上游出错' } }, { status: 502 });
    const stream = new ReadableStream({
      start(controller) { controller.enqueue(sse({ choices: [{ delta: { content: '云端正在生成' } }] })); },
      async pull(controller) {
        await delay(marker === '等待取消' ? 3800 : 800);
        controller.enqueue(sse({ choices: [{ delta: { content: '，后台任务已完成。' }, finish_reason: 'stop' }] }));
        controller.close();
      },
    });
    return new MockResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const mf = new Miniflare(convertV4MiniflareOptions({
    host: '127.0.0.1', port: 0,
    workers: [
      {
        name: 'aera-web', modules: true, compatibilityDate, scriptPath: resolve('.preview/pages-bundle/index.js'),
        durableObjects: { AERA_JOBS: { className: 'GenerationJob', scriptName: 'aera-web-jobs', useSQLite: true } },
        assets: { directory: resolve('dist'), binding: 'ASSETS', run_worker_first: ['/api/*'],
          routerConfig: { has_user_worker: true } },
        outboundService,
      },
      {
        name: 'aera-web-jobs', modules: true, compatibilityDate, scriptPath: resolve('.preview/jobs-worker.mjs'),
        durableObjects: { AERA_JOB_SELF: { className: 'GenerationJob', useSQLite: true } },
        outboundService,
      },
    ],
  }));
  t.after(() => mf.dispose());
  const base = (await mf.ready).origin;
  const pageAsset = await fetch(base + '/');
  assert.equal(pageAsset.status, 200);
  assert.match(pageAsset.headers.get('Content-Security-Policy'), /frame-ancestors 'none'/);
  assert.match(await (await fetch(base + '/runtime.mjs')).text(), /hosted = true/);
  assert.equal((await fetch(base + '/vendor/pdf.worker.mjs')).status, 200);
  assert.equal((await fetch(base + '/vendor/fonts/KaTeX_Main-Regular.woff2')).status, 200);

  const owner = randomUUID();
  const provider = { api: 'https://' + providerHost + '/v1', key: 'mock-key', protocol: 'chat_completions' };
  const data = (parts, search = false) => ({
    provider, model: { id: 'mock-model', context: 4_000_000, max: 4096 },
    config: { temperature: .7, topP: .9, search }, options: { searchMode: 'tavily', tavilyKey: 'tavily-fixture' },
    messages: [{ role: 'user', parts }],
  });
  const send = (path, method = 'GET', body, extra = {}) => fetch(base + path, {
    method, body, headers: { 'X-Aera-Session': owner, ...(body instanceof Blob && { 'Content-Type': body.type }), ...extra },
  });

  const models = await send('/api/models', 'POST', JSON.stringify(provider), { 'Content-Type': 'application/json' });
  const modelResult = await models.text();
  assert.equal(models.status, 200, modelResult);
  assert.deepEqual(JSON.parse(modelResult), { models: ['mock-model'] });
  const redirected = await send('/api/models', 'POST', JSON.stringify({ ...provider, api: provider.api + '/redirect' }),
    { 'Content-Type': 'application/json' });
  assert.match((await redirected.json()).error, /HTTP 302/);
  const privateAddress = await send('/api/models', 'POST', JSON.stringify({ ...provider, api: 'http://127.0.0.1/v1' }),
    { 'Content-Type': 'application/json' });
  assert.equal(privateAddress.status, 400);
  assert.equal((await send('/api/models', 'POST', JSON.stringify(provider),
    { 'Content-Type': 'application/json', Origin: 'https://another-site.example.org' })).status, 403);

  async function start(input) {
    const path = '/api/jobs/' + randomUUID();
    const response = await send(path, 'POST', hostedJobBody(input));
    assert.equal(response.status, 202, await response.text());
    return path;
  }
  async function poll(path, matches) {
    for (let tries = 0; tries < 180; tries++) {
      const result = await send(path);
      assert.equal(result.status, 200, await result.clone().text());
      const value = await result.json();
      if (matches(value)) return value;
      if (value.phase === 'error') assert.fail(value.error);
      await delay(50);
    }
    assert.fail('未观察到任务状态 ' + path);
  }

  const text = 'PDF 一段带双引号、转义符与换行的长文本。\n"\\'.repeat(40000);
  const file = process.env.AERA_CLOUD_FULL_PAYLOAD ? 'a'.repeat(89478486) + '==' : 'a'.repeat(7 * 1024 * 1024);
  const pdf = await start(data([{ type: 'text', text }, { type: 'pdf', mime: 'application/pdf', name: '测试.pdf', base64: file }], true));
  await delay(1400);
  const complete = await poll(pdf, snapshot => snapshot.phase === 'done');
  assert.equal(complete.text, '云端正在生成，后台任务已完成。');
  assert.deepEqual(complete.sources, [{ title: '资料', url: 'https://example.org/' }]);
  assert.equal(captured[0].marker, text);
  assert.equal(captured[0].length, file.length + 'data:application/pdf;base64,'.length);
  assert.match(captured[0].system, /Tavily 工具记录/);
  assert.equal((await send(pdf, 'GET', undefined, { 'X-Aera-Session': randomUUID() })).status, 404);
  assert.equal((await send(pdf, 'DELETE')).status, 200);
  assert.equal((await send(pdf)).status, 200);
  await send(pdf + '?discard=1', 'DELETE');
  assert.equal((await send(pdf)).status, 404);

  const cancel = await start(data([{ type: 'text', text: '等待取消' }]));
  await poll(cancel, snapshot => snapshot.text === '云端正在生成');
  await send(cancel, 'DELETE');
  await poll(cancel, snapshot => snapshot.phase === 'cancelled');

  const failure = await start(data([{ type: 'text', text: 'HTTP 502' }]));
  const failed = await poll(failure, snapshot => snapshot.phase === 'error');
  assert.match(failed.error, /HTTP 502.*已隐藏/s);
  assert.doesNotMatch(failed.error, /mock-key/);

  const channel = process.env.AERA_BROWSER_CHANNEL ?? (process.platform === 'win32' ? 'msedge' : undefined);
  const browser = await chromium.launch({ channel });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(7000);
    await page.goto(base);
    await page.getByText('历史保存在此浏览器，生成任务由云端暂存').waitFor();
    await page.getByRole('button', { name: '设置', exact: true }).click();
    await page.getByRole('button', { name: '新增供应商' }).click();
    await page.getByLabel('供应商名称').fill('边缘测试供应商');
    await page.getByLabel('API 地址').fill(provider.api);
    await page.getByRole('button', { name: '获取模型列表' }).click();
    await page.getByRole('button', { name: '添加', exact: true }).click();
    await page.getByRole('button', { name: '保存供应商' }).click();
    await page.getByRole('textbox', { name: '输入消息' }).fill('从 Pages 发起对话');
    await page.getByRole('textbox', { name: '输入消息' }).press('Enter');
    await page.getByText('云端正在生成', { exact: true }).waitFor();
    await page.reload();
    await page.getByText(complete.text, { exact: true }).waitFor();
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
