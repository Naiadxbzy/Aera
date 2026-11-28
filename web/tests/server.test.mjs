import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { createAeraServer } from '../server.mjs';
import { endpoint, prepareRequest } from '../protocols.mjs';

const image = { type: 'image', mime: 'image/png', base64: 'YWJj' };
const pdf = { type: 'pdf', mime: 'application/pdf', name: 'file.pdf', base64: 'YWJj' };

function input(base, protocol = 'chat_completions', options = {}) {
  return {
    provider: { api: base, protocol, key: 'test-token' },
    model: { id: { chat_completions: 'fixture', responses: 'gpt-5', anthropic: 'claude-sonnet-4-6', gemini: 'gemini-2.5-flash' }[protocol],
      max: 4096, context: 128000 },
    config: { system: '行为约定', temperature: 0.6, topP: 0.9, effort: 'low', search: false },
    options: { searchMode: 'automatic', urlContext: false, code: false, ...options },
    messages: [{ role: 'user', parts: [{ type: 'text', text: '问题' }, image, pdf] }],
  };
}

async function bind(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return 'http://127.0.0.1:' + server.address().port;
}

test('four protocol payloads encode instructions, raw image/PDF and reasoning', () => {
  const base = 'http://127.0.0.1:9999';
  const chat = prepareRequest(input(base + '/v1/chat/completions'));
  assert.equal(chat.url.pathname, '/v1/chat/completions');
  assert.equal(chat.body.messages[0].content, '行为约定');
  assert.equal(chat.body.messages[1].content[1].type, 'image_url');
  assert.equal(chat.body.messages[1].content[2].file.file_data, 'data:application/pdf;base64,YWJj');
  const responses = prepareRequest(input(base + '/v1', 'responses', { code: true }));
  assert.equal(responses.body.instructions, '行为约定');
  assert.equal(responses.body.store, false);
  assert.equal(responses.body.reasoning.effort, 'low');
  assert.equal(responses.body.input[0].content[2].filename, 'file.pdf');
  assert.deepEqual(responses.tools, ['code_interpreter']);
  const anthropic = prepareRequest(input(base, 'anthropic', { urlContext: true }));
  assert.equal(anthropic.url.pathname, '/v1/messages');
  assert.equal(anthropic.body.messages[0].content[2].source.type, 'base64');
  assert.equal(anthropic.body.thinking.type, 'adaptive');
  assert.ok(anthropic.body.system.startsWith('行为约定'));
  assert.deepEqual(anthropic.tools, ['web_fetch']);
  const gemini = prepareRequest(input(base, 'gemini'));
  assert.equal(gemini.url.pathname, '/v1beta/models/gemini-2.5-flash:streamGenerateContent');
  assert.equal(gemini.url.search, '?alt=sse');
  assert.equal(gemini.body.contents[0].parts[1].inlineData.mimeType, 'image/png');
  assert.equal(gemini.body.generationConfig.thinkingConfig.thinkingBudget, 1024);
});

test('endpoint validation preserves origins and rejects credential-bearing URLs', () => {
  assert.equal(endpoint('https://site.test/edge/v1/messages', 'anthropic', '', true).href, 'https://site.test/edge/v1/models');
  assert.equal(endpoint('https://site.test/v1beta/models/demo', 'gemini', '', true).pathname, '/v1beta/models');
  for (const base of ['file:///foo', 'http://token@localhost', 'https://site.test/v1?key=secret', 'https://site.test/v1#fragment']) {
    assert.throws(() => endpoint(base, 'responses', 'fixture'));
  }
});

const event = (response, value) => response.write('data: ' + JSON.stringify(value) + '\r\n\r\n');

test('local server serves the desktop UI and all bundled dependencies', async t => {
  const server = createAeraServer();
  t.after(() => server.close());
  const base = await bind(server);
  const assets = ['/', '/style.css', '/app.mjs', '/generation.mjs', '/pdf-preview.mjs', '/vendor/marked.js',
    '/vendor/purify.js', '/vendor/fflate.js', '/vendor/katex.js', '/vendor/auto-render.js', '/vendor/highlight.js',
    '/vendor/highlight.css', '/vendor/katex.css', '/vendor/fonts/KaTeX_Main-Regular.woff2',
    '/vendor/pdf.mjs', '/vendor/pdf.worker.mjs'];
  await Promise.all(assets.map(async pathname => {
    const asset = await fetch(base + pathname, { method: 'HEAD' });
    assert.equal(asset.status, 200, pathname);
  }));
  assert.equal((await fetch(base + '/package.json')).status, 404);
});

test('model lookup, detached streaming across protocols, owner isolation and errors', async t => {
  const mock = createServer(async (req, res) => {
    if (req.url === '/v1/models') {
      assert.equal(req.headers.authorization, 'Bearer test-token');
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ data: [{ id: 'fixture' }] }));
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    if (body.instructions === 'reject tools') {
      res.writeHead(502, { 'Content-Type': 'application/json', 'x-request-id': 'id-502' });
      res.end(JSON.stringify({ error: { message: 'upstream failed test-token' } }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    if (req.url === '/v1/responses') {
      event(res, { type: 'response.output_text.delta', delta: '中', sequence_number: 4 });
      event(res, { type: 'response.output_text.delta', delta: '中', sequence_number: 4 });
      event(res, { type: 'response.reasoning_summary_text.delta', delta: '思考' });
      await delay(110);
      event(res, { type: 'response.completed', response: { output: [{ type: 'message', content: [
        { type: 'output_text', text: '中文完成', annotations: [{ title: '参考', url: 'https://example.org' }] },
      ] }] } });
    } else if (req.url === '/v1/messages') {
      assert.equal(req.headers['x-api-key'], 'test-token');
      event(res, { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
      event(res, { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '中文完成' } });
      event(res, { type: 'message_stop' });
    } else if (req.url.includes('streamGenerateContent')) {
      assert.equal(req.headers['x-goog-api-key'], 'test-token');
      event(res, { candidates: [{ content: { parts: [{ text: '中文完成' }] }, finishReason: 'STOP' }] });
    } else {
      event(res, { choices: [{ delta: { content: '中文' } }] });
      if (body.messages[0].content === 'stop test') await delay(600);
      event(res, { choices: [{ delta: { content: '完成' }, finish_reason: 'stop' }] });
    }
    res.end();
  });
  const server = createAeraServer();
  const base = await bind(server), upstream = await bind(mock);
  t.after(() => { server.close(); mock.close(); });
  const session = randomUUID();
  const headers = { 'X-Aera-Session': session, 'Content-Type': 'application/json' };
  const send = (path, method = 'GET', body, extra) => fetch(base + path, {
    method, headers: { ...headers, ...extra }, body: body && JSON.stringify(body),
  });
  const models = await send('/api/models', 'POST', input(upstream + '/v1').provider);
  assert.deepEqual(await models.json(), { models: ['fixture'] });
  assert.equal((await send('/api/models', 'POST', input(upstream + '/v1').provider, { Origin: 'https://other.test' })).status, 403);

  async function wait(path) {
    let snapshot;
    for (let tries = 0; tries < 80; tries++) {
      const lookup = await send(path);
      assert.equal(lookup.status, 200);
      snapshot = await lookup.json();
      if (snapshot.phase !== 'working') return snapshot;
      await delay(20);
    }
    assert.fail('generation did not finish');
  }

  for (const protocol of ['chat_completions', 'responses', 'anthropic', 'gemini']) {
    const path = '/api/jobs/' + randomUUID();
    const body = input(upstream + '/v1', protocol);
    assert.equal((await send(path, 'POST', body)).status, 202);
    assert.equal((await send(path, 'GET', null, { 'X-Aera-Session': randomUUID() })).status, 404);
    const result = await wait(path);
    assert.equal(result.phase, 'done');
    assert.equal(result.text, '中文完成');
    if (protocol === 'responses') {
      assert.equal(result.reasoning, '思考');
      assert.equal(result.sources.length, 1);
    }
  }

  const failed = '/api/jobs/' + randomUUID();
  const failure = input(upstream + '/v1', 'responses', { code: true });
  failure.config.system = 'reject tools';
  await send(failed, 'POST', failure);
  const result = await wait(failed);
  assert.equal(result.phase, 'error');
  assert.ok(result.skipTools);
  assert.match(result.error, /502.*已隐藏/s);
  assert.match(result.error, /id-502/);

  const cancelled = '/api/jobs/' + randomUUID();
  const stopped = input(upstream + '/v1');
  stopped.config.system = 'stop test';
  await send(cancelled, 'POST', stopped);
  await send(cancelled, 'DELETE');
  assert.equal((await wait(cancelled)).phase, 'cancelled');
});
