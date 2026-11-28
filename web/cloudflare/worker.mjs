import { DurableObject } from 'cloudflare:workers';
import { deadline, initialSnapshot, jobRetention, runGeneration } from '../generation-core.mjs';
import { response, validateProvider } from './api.mjs';
import { payloadBody, receivePayload } from './payload.mjs';

const watchdogDelay = deadline + 2 * 60 * 1000;
const resultChunkSize = 512 * 1024;

function terminalError(message) {
  return { ...initialSnapshot(), phase: 'error', status: '请求失败', error: message, revision: 1 };
}

export class GenerationJob extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.storage = ctx.storage;
    this.active = null;
    this.submitting = false;
  }

  async fetch(request) {
    try {
      if (request.method === 'POST') return await this.submit(request);
      const state = await this.storage.get('state');
      if (!state) return response({ error: '找不到这次生成，结果可能已经过期。' }, 404);
      if (request.method === 'GET') return response(await this.snapshot(state));
      if (request.method === 'DELETE') {
        await this.cancel(state, new URL(request.url).searchParams.get('discard') === '1');
        return response({ ok: true });
      }
      return response({ error: '方法不可用。' }, 405);
    } catch {
      return response({ error: '云端任务暂时不可用，请稍后重试。' }, 503);
    }
  }

  async submit(request) {
    if (this.submitting) return response({ error: '正在接收本次生成请求。' }, 409);
    this.submitting = true;
    try {
      const state = await this.storage.get('state');
      if (state) return response(await this.snapshot(state), 202);
      await this.storage.setAlarm(Date.now() + watchdogDelay);
      this.storage.sql.exec('CREATE TABLE IF NOT EXISTS upload (ref INTEGER, block INTEGER, data BLOB NOT NULL, PRIMARY KEY (ref, block))');
      let input;
      try {
        input = await receivePayload(request, validateProvider, (ref, block, data) => {
          this.storage.sql.exec('INSERT INTO upload VALUES (?, ?, ?)', ref, block, data);
        });
      } catch (error) {
        await this.storage.deleteAll();
        await this.storage.deleteAlarm();
        return response({ error: error instanceof SyntaxError ? '请求包含无效 JSON。' : error.message }, 400);
      }
      await this.storage.transaction(async txn => {
        await txn.put({ input, state: { phase: 'queued' } });
        await txn.setAlarm(Date.now());
      });
      return response(initialSnapshot(), 202);
    } finally { this.submitting = false; }
  }

  async snapshot(state) {
    if (this.active) return this.active.snapshot;
    if (state.phase === 'queued') return initialSnapshot();
    if (state.phase === 'running') {
      return terminalError('云端运行环境中断了生成。请手动重试。');
    }
    const keys = Array.from({ length: state.chunks }, (_, index) => 'result:' + index);
    const values = await this.storage.get(keys);
    return JSON.parse(keys.map(key => values.get(key)).join(''));
  }

  async cancel(state, discard) {
    if (this.active) this.active.controller.abort();
    else if (state.phase === 'queued') {
      await this.finish({ ...initialSnapshot(), phase: 'cancelled', status: '已停止', revision: 1 });
    } else if (discard && state.phase === 'finished') {
      await this.storage.deleteAll();
      await this.storage.deleteAlarm();
    }
  }

  async finish(snapshot) {
    const serialized = JSON.stringify(snapshot);
    const result = {};
    let chunks = 0;
    for (let offset = 0; offset < serialized.length; offset += resultChunkSize) {
      result['result:' + chunks++] = serialized.slice(offset, offset + resultChunkSize);
    }
    this.storage.sql.exec('DELETE FROM upload');
    await this.storage.transaction(async txn => {
      await txn.delete('input');
      await txn.put({ ...result, state: { phase: 'finished', chunks } });
      await txn.setAlarm(Date.now() + jobRetention);
    });
  }

  async alarm() {
    const state = await this.storage.get('state');
    if (!state || state.phase === 'finished') {
      if (!this.submitting) await this.storage.deleteAll();
      return;
    }
    if (state.phase === 'running') {
      if (this.active) this.active.controller.abort();
      else await this.finish(terminalError('云端运行环境中断了生成。请手动重试。'));
      return;
    }
    if (this.active) return;
    const active = { controller: new AbortController(), snapshot: initialSnapshot() };
    this.active = active;
    try {
      const stored = await this.storage.get('input');
      await this.storage.transaction(async txn => {
        await txn.put('state', { phase: 'running' });
        await txn.setAlarm(Date.now() + watchdogDelay);
      });
      await runGeneration(active, stored.input, {
        query: stored.query,
        encode: body => payloadBody(body, stored, (ref, block) =>
          this.storage.sql.exec('SELECT data FROM upload WHERE ref = ? AND block = ?', ref, block).one().data),
      });
      await this.finish(active.snapshot);
    } finally { this.active = null; }
  }
}

export default {
  fetch() { return new Response('Not found', { status: 404 }); },
};
