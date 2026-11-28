import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { buffer } from 'node:stream/consumers';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from 'playwright';
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { createAeraServer } from '../server.mjs';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return 'http://127.0.0.1:' + server.address().port;
}

function pdfFixture() {
  const commands = '0 0 1 rg 30 30 70 45 re f\n';
  const parts = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 120] /Resources << >> /Contents 4 0 R >>',
    '<< /Length ' + commands.length + ' >>\nstream\n' + commands + 'endstream',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  for (const [index, part] of parts.entries()) {
    offsets.push(pdf.length);
    pdf += (index + 1) + ' 0 obj\n' + part + '\nendobj\n';
  }
  const xref = pdf.length;
  pdf += 'xref\n0 5\n0000000000 65535 f \n';
  for (const offset of offsets) pdf += String(offset).padStart(10, '0') + ' 00000 n \n';
  pdf += 'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n' + xref + '\n%%EOF\n';
  return Buffer.from(pdf);
}

test('desktop chat, PDF, background resume and Android backup round trip', { timeout: 45_000 }, async t => {
  const sent = [];
  const mock = createServer(async (request, response) => {
    if (request.url === '/v1/models') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end('{"data":[{"id":"gpt-5.2"}]}');
      return;
    }
    const input = JSON.parse((await buffer(request)).toString());
    sent.push(input);
    const lastContent = input.messages.at(-1).content;
    if (Array.isArray(lastContent) && lastContent.some(part => part.text === '测试 PDF 转图') &&
        lastContent.some(part => part.type === 'file')) {
      response.writeHead(400, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: { message: 'PDF files are unsupported' } }));
      return;
    }
    const event = value => response.write('data: ' + JSON.stringify(value) + '\n\n');
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    event({ choices: [{ delta: { content: '开始流式回复。\n\n' } }] });
    await delay(1050);
    if (response.destroyed) return;
    const content = '**完成测试**，公式 $x^2$。\n\n```html\n<div><strong>静态预览</strong></div>\n```';
    event({ choices: [{ delta: { content }, finish_reason: 'stop' }], usage: { total_tokens: 110 } });
    response.end('data: [DONE]\n\n');
  });
  const server = createAeraServer();
  const [base, upstream] = await Promise.all([listen(server), listen(mock)]);
  t.after(() => { mock.close(); server.close(); });
  const channel = process.env.AERA_BROWSER_CHANNEL ?? (process.platform === 'win32' ? 'msedge' : undefined);
  const browser = await chromium.launch({ channel });
  t.after(() => browser.close());
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await context.newPage();
  page.setDefaultTimeout(7000);
  const failures = [];
  page.on('pageerror', error => failures.push(error.message));
  page.on('response', response => { if (response.url().includes('/vendor/') && !response.ok()) failures.push(response.status() + ' ' + response.url()); });
  await page.goto(base);
  await page.getByRole('heading', { name: '今天想聊些什么？' }).waitFor();
  const welcome = page.getByRole('heading', { name: '今天想聊些什么？' });
  const composer = page.getByRole('form', { name: '消息编辑器' });
  const titleBounds = await welcome.boundingBox();
  const composerBounds = await composer.boundingBox();
  assert.ok(composerBounds.y - titleBounds.y - titleBounds.height < 45);
  assert.equal(await page.getByRole('button', { name: /起草邮件|整理资料|拆解问题/ }).count(), 0);
  if (process.env.AERA_SCREENSHOTS) await page.screenshot({ path: '.preview/desktop.png' });
  assert.ok(await page.getByRole('complementary').isVisible());
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 1440);

  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '新增供应商' }).click();
  await page.getByLabel('供应商名称').fill('模拟供应商');
  await page.getByLabel('API 地址').fill(upstream + '/v1');
  await page.getByRole('button', { name: '获取模型列表' }).click();
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await page.getByRole('button', { name: '保存供应商' }).click();
  await page.getByRole('button', { name: 'gpt-5.2' }).waitFor();
  const effort = page.locator('#effort');
  const popover = page.getByRole('region', { name: '思考强度', exact: true });
  await effort.click();
  await popover.waitFor();
  assert.equal(await page.locator('#dialog').isVisible(), false);
  assert.equal(await effort.getAttribute('aria-expanded'), 'true');
  await page.waitForFunction(() => {
    const anchor = document.querySelector('#effort').getBoundingClientRect();
    const panel = document.querySelector('#reasoning-popover').getBoundingClientRect();
    return panel.top > 0 && panel.bottom <= anchor.top - 5;
  });

  const range = page.getByRole('slider', { name: '思考强度滑动条' });
  const reset = page.getByRole('button', { name: '恢复自动思考强度' });
  assert.equal(await range.getAttribute('max'), '4');
  assert.ok(await reset.isDisabled());
  await range.press('End');
  assert.equal(await range.getAttribute('aria-valuetext'), '极高，第 5 档，共 5 档');
  assert.equal(await effort.getAttribute('aria-label'), '思考强度 · 极高');
  await reset.click();
  assert.equal(await range.getAttribute('aria-valuetext'), '模型默认');
  assert.equal(await effort.getAttribute('aria-label'), '思考强度 · Auto');

  const rail = await page.locator('.reasoning-rail').boundingBox();
  const stopX = fraction => rail.x + 18 + (rail.width - 36) * fraction;
  const center = rail.y + rail.height / 2;
  await page.mouse.move(stopX(.25), center);
  await page.mouse.down();
  await page.mouse.move(stopX(.75), center, { steps: 8 });
  await page.mouse.up();
  assert.equal(await range.getAttribute('aria-valuetext'), '高，第 4 档，共 5 档');
  assert.equal(await effort.getAttribute('aria-label'), '思考强度 · 高');
  await page.waitForFunction(() => {
    const canvas = document.querySelector('.reasoning-rail canvas');
    const width = canvas.getBoundingClientRect().width;
    const thumb = (18 + (width - 36) * .75) / width * canvas.width;
    const context = canvas.getContext('2d');
    const pixel = context.getImageData(Math.round(thumb), Math.floor(canvas.height / 2), 1, 1).data;
    const accent = getComputedStyle(canvas).getPropertyValue('--accent-button').trim();
    const expectedColor = accent.slice(1).match(/../g).map(hex => parseInt(hex, 16));
    const appliedColor = context.getImageData(Math.round(40 * canvas.width / width), Math.floor(canvas.height * .72), 1, 1).data;
    return pixel.every(channel => channel >= 250) && expectedColor.every((channel, index) => appliedColor[index] === channel);
  });
  if (process.env.AERA_SCREENSHOTS) await page.screenshot({ path: '.preview/reasoning.png' });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => {
    const box = document.querySelector('#reasoning-popover').getBoundingClientRect();
    const anchor = document.querySelector('#effort').getBoundingClientRect();
    return box.left >= 8 && box.right < window.innerWidth && box.top >= anchor.bottom + 5;
  });
  await page.getByRole('textbox', { name: '输入消息' }).click();
  await popover.waitFor({ state: 'hidden' });
  assert.equal(await effort.getAttribute('aria-expanded'), 'false');
  await page.setViewportSize({ width: 1440, height: 900 });
  await effort.click();
  assert.equal(await range.getAttribute('aria-valuetext'), '高，第 4 档，共 5 档');
  await range.press('Escape');
  await popover.waitFor({ state: 'hidden' });
  await effort.click();
  await popover.getByRole('button', { name: 'gpt-5.2', exact: true }).click();
  await popover.waitFor({ state: 'hidden' });
  await page.getByRole('dialog', { name: '选择模型' }).waitFor();
  await page.keyboard.press('Escape');
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.locator('#attachments').setInputFiles({ name: 'sample.pdf', mimeType: 'application/pdf', buffer: pdfFixture() });
  await page.getByRole('button', { name: 'sample.pdf', exact: true }).click();
  await page.getByRole('img', { name: '第 1 页 / 共 1 页' }).waitFor();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  await page.getByRole('textbox', { name: '输入消息' }).fill('请读取这个 PDF');
  await page.getByRole('textbox', { name: '输入消息' }).press('Enter');
  await page.getByText('开始流式回复。', { exact: true }).waitFor();
  await page.getByRole('textbox', { name: '输入消息' }).fill('第二个问题');
  await page.getByRole('textbox', { name: '输入消息' }).press('Enter');
  await page.getByText('1 条消息待发送', { exact: true }).waitFor();
  await page.reload();
  await page.getByText('完成测试', { exact: true }).first().waitFor();
  await page.getByText('完成测试', { exact: true }).nth(1).waitFor();
  assert.equal(sent.length, 2);
  assert.equal(sent[0].reasoning_effort, 'high');
  assert.equal(sent[1].reasoning_effort, 'high');
  await page.getByRole('button', { name: '思考强度 · 高' }).waitFor();
  assert.equal(sent[0].messages.at(-1).content[1].file.filename, 'sample.pdf');
  assert.ok(await page.locator('.katex').first().isVisible());
  assert.ok(await page.locator('.hljs').first().isVisible());
  await page.getByRole('button', { name: '预览', exact: true }).first().click();
  assert.equal(await page.frameLocator('iframe[title="静态代码预览"]').getByText('静态预览').textContent(), '静态预览');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  if (process.env.AERA_SCREENSHOTS) await page.screenshot({ path: '.preview/conversation.png' });

  await page.getByRole('button', { name: '编辑', exact: true }).last().click();
  await page.getByLabel('消息内容').fill('编辑后的分支');
  await page.getByRole('button', { name: '保存并生成分支' }).click();
  await page.getByText('对话分支 2 / 2', { exact: true }).waitFor();
  await page.getByRole('button', { name: '上一分支' }).waitFor();
  await page.getByRole('button', { name: '上一分支' }).click();
  await page.getByText('第二个问题', { exact: true }).waitFor();
  assert.equal(await page.getByText('编辑后的分支', { exact: true }).count(), 0);

  await page.getByRole('button', { name: '新建对话', exact: false }).click();
  await page.getByRole('button', { name: '思考强度 · Auto' }).waitFor();
  await page.locator('#attachments').setInputFiles({ name: 'convert.pdf', mimeType: 'application/pdf', buffer: pdfFixture() });
  await page.getByRole('button', { name: 'convert.pdf', exact: true }).waitFor();
  await page.getByRole('textbox', { name: '输入消息' }).fill('测试 PDF 转图');
  await page.getByRole('textbox', { name: '输入消息' }).press('Enter');
  await page.getByText('完成测试', { exact: true }).waitFor();
  const fallback = sent.at(-1).messages.at(-1).content;
  assert.equal(fallback[1].type, 'image_url');
  assert.match(fallback[1].image_url.url, /^data:image\/jpeg;base64,/);

  const backup = zipSync({
    'conversations.json': strToU8(JSON.stringify({ format: 'ochat-backup', version: 1, sessions: [{
      title: 'Android 导入范例', updatedAt: Date.now(), messages: [{
        user: true, text: '附件测试', attachments: [{ uri: 'content://example/1', mime: 'text/plain',
          displayName: '备份附件.txt', backupFile: 'attachments/fixture-entry' }],
      }],
    }] })),
    'attachments/fixture-entry': strToU8('来自 Android 备份的文字'),
  });
  const importInput = page.locator('#backup');
  await importInput.setInputFiles({ name: 'android.zip', mimeType: 'application/zip', buffer: Buffer.from(backup) });
  await page.getByRole('heading', { name: 'Android 导入范例' }).waitFor();
  await page.getByRole('button', { name: '备份附件.txt' }).click();
  await page.getByText('来自 Android 备份的文字', { exact: true }).waitFor();
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '数据与备份' }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出对话 ZIP' }).click();
  const exported = unzipSync(new Uint8Array(await buffer(await (await download).createReadStream())));
  const conversations = JSON.parse(strFromU8(exported['conversations.json']));
  const imported = conversations.sessions.find(session => session.title === 'Android 导入范例');
  assert.equal(conversations.format, 'ochat-backup');
  assert.equal(strFromU8(exported[imported.messages[0].attachments[0].backupFile]), '来自 Android 备份的文字');
  assert.doesNotMatch(strFromU8(exported['conversations.json']), /apiKey|tavilyKey|Authorization/i);
  assert.deepEqual(failures, []);
  await page.close();
});

test('compact viewport has a collapsible history sidebar and usable composer', { timeout: 20_000 }, async t => {
  const server = createAeraServer();
  const base = await listen(server);
  t.after(() => server.close());
  const channel = process.env.AERA_BROWSER_CHANNEL ?? (process.platform === 'win32' ? 'msedge' : undefined);
  const browser = await chromium.launch({ channel });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme: 'dark' });
  page.setDefaultTimeout(6000);
  await page.goto(base);
  await page.getByRole('heading', { name: '今天想聊些什么？' }).waitFor();
  assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'dark');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
  assert.equal(await page.getByRole('complementary').count(), 0);
  await page.getByRole('button', { name: '打开对话侧栏' }).click();
  await page.getByRole('complementary').waitFor({ state: 'visible' });
  await page.getByRole('button', { name: '关闭侧栏' }).click({ position: { x: 370, y: 410 } });
  await page.getByRole('complementary').waitFor({ state: 'hidden' });
  await page.getByRole('textbox', { name: '输入消息' }).fill('窄屏消息');
  await page.getByRole('textbox', { name: '输入消息' }).press('Shift+Enter');
  assert.equal(await page.getByRole('textbox', { name: '输入消息' }).inputValue(), '窄屏消息\n');
  if (process.env.AERA_SCREENSHOTS) await page.screenshot({ path: '.preview/compact.png' });
});
