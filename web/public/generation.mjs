import { canonical, searchRouting } from './capabilities.mjs';
import { attachmentParts, uid } from './files.mjs';

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

export function createGeneration({ state, api, lookup, commit, paint, notify }) {
  const attached = new Set();
  const ocrCache = new Map();
  const controls = new Map();

  function request(provider, model, config, options, messages) {
    return {
      provider: { key: state.keys[provider.id] ?? '', api: provider.api, protocol: provider.protocol },
      model, config, options: { ...options, tavilyKey: state.keys.tavily ?? '' }, messages,
    };
  }

  async function compactReply(input, signal) {
    const path = '/api/jobs/' + uid();
    try {
      let snapshot = await api(path, 'POST', input, signal);
      while (snapshot.phase === 'working') {
        await delay(450);
        signal?.throwIfAborted();
        snapshot = await api(path, 'GET', undefined, signal);
      }
      if (snapshot.phase !== 'done' || !snapshot.text.trim()) throw new Error(snapshot.error || '模型没有返回结果。');
      if (/length|max_tokens|token_limit/i.test(snapshot.status)) throw new Error('输出达到上限，请调整识别模型的输出限制后重试。');
      return snapshot.text;
    } finally { void api(path + '?discard=1', 'DELETE').catch(() => {}); }
  }

  function choose(value) {
    if (!value) return null;
    try {
      const [providerId, modelId] = JSON.parse(value);
      return lookup({ providerId, modelId });
    } catch { return null; }
  }

  const quietConfig = { effort: 'default', search: false, temperature: 0, topP: 1 };
  const quietTools = { searchMode: 'automatic', urlContext: false, code: false };

  function ocrOperation(selection, signal) {
    return async (file, parts) => {
      const cacheKey = JSON.stringify([file.id, selection.provider.id, selection.provider.api, selection.model.id]);
      if (ocrCache.has(cacheKey)) return ocrCache.get(cacheKey);
      const items = [];
      for (let index = 0; index < parts.length; index++) {
        signal.throwIfAborted();
        const text = await compactReply(request(selection.provider, selection.model, quietConfig, quietTools, [{
          role: 'user',
          parts: [
            { type: 'text', text: '忠实转录图像中的可见文字和表格，保留内容顺序。只返回转录文字；无法辨认时标注 [无法辨认]。' },
            parts[index],
          ],
        }]), signal);
        if (!text.trim() || /^\s*\[无法辨认\]\s*$/.test(text)) throw new Error(file.name + ' 没有可识别的文字。');
        items.push(parts.length > 1 ? '第 ' + (index + 1) + ' 页\n' + text : text);
      }
      const result = items.join('\n\n');
      if (ocrCache.size >= 80) ocrCache.delete(ocrCache.keys().next().value);
      ocrCache.set(cacheKey, result);
      return result;
    };
  }

  async function wireHistory(messages, provider, model, flags, forcePdf, signal, onStatus) {
    const official = new URL(provider.api).hostname === 'api.deepseek.com';
    const visualDeepSeek = /^(deepseek-flash|deepseek-v4-flash(?:-vision-exp)?)$/.test(canonical(model.id));
    const output = [];
    const newestUser = messages.findLast(message => message.role === 'user');
    for (const message of messages) {
      signal.throwIfAborted();
      if (!message.text && !message.files.length) continue;
      if (message.error || message.cancelled) continue;
      const parts = message.text ? [{ type: 'text', text: message.text }] : [];
      for (const file of message.files) {
        if (file.missing && message !== newestUser) {
          parts.push({ type: 'text', text: '[备份中的附件不可用：' + file.name + ']' });
          continue;
        }
        const pdf = file.mime === 'application/pdf';
        const isImage = /^image\/(?:jpeg|png|gif|webp)$/.test(file.mime);
        const setting = pdf ? 'pdfOcr' : isImage ? 'imageOcr' : '';
        const choice = setting && (model[setting] === undefined || model[setting] === 'inherit' ? flags[setting] : model[setting]);
        const selection = choose(choice);
        if (choice && !selection) throw new Error('附件识别模型已不可用，请更新设置后重试。');
        if (pdf && official && !visualDeepSeek && !selection) {
          throw new Error('此 DeepSeek 官方模型不支持 PDF 原文件，请先配置 PDF 转文字模型。');
        }
        if (isImage && official && !visualDeepSeek && !selection) {
          throw new Error('此 DeepSeek 官方模型不支持图片输入，请先配置图片转文字模型。');
        }
        onStatus(selection ? '正在识别 ' + file.name : '正在处理 ' + file.name);
        const encoded = await attachmentParts(file, {
          pdfAsImages: pdf && (forcePdf || (official && visualDeepSeek)),
          ocr: selection ? ocrOperation(selection, signal) : null, signal,
        });
        parts.push(...encoded);
      }
      if (parts.length) output.push({ role: message.role, parts, ...(message.reasoning && { reasoning: message.reasoning }) });
    }
    return output;
  }

  async function maybeTitle(session, selected, flags) {
    if (!flags.autoTitle || session.named || session.titleRequested) return;
    const first = session.messages.find(message => message.role === 'user' && message.text.trim());
    if (!first) return;
    session.titleRequested = true;
    const model = choose(flags.titleModel) ?? selected;
    if (!model) return;
    commit();
    try {
      const answer = await compactReply(request(model.provider, model.model,
        { ...quietConfig, system: flags.titlePrompt, max: 512 }, quietTools,
        [{ role: 'user', parts: [{ type: 'text', text: first.text.slice(0, 6000) }] }]));
      if (session.named || !state.sessions.includes(session)) return;
      const title = answer.trim().split('\n')[0].replace(/^[#\s"'“‘]+|["'”’\s]+$/g, '').slice(0, 48);
      if (title) { session.title = title; commit(); paint(session, false); }
    } catch { /* The first-message title is retained when the naming model fails. */ }
  }

  function complete(session, job, result, error = '') {
    if (session.job !== job) return;
    if (result.phase !== 'cancelled' || result.text || result.reasoning) {
      session.messages.push({
        id: job.id, role: 'assistant', model: job.model, text: result.text || '', files: [],
        reasoning: result.reasoning || '', timestamp: Date.now(), sources: result.sources ?? [],
        tools: result.tools ?? [], usage: result.usage, pricing: job.pricing,
        omitted: result.omitted, error: error || result.error || '',
        status: result.status, cancelled: result.phase === 'cancelled',
        skipSearch: result.skipSearch, skipTools: result.skipTools,
        ...((result.skipSearch || result.skipTools) && { replayConfig: job.config }),
      });
    }
    session.job = null;
    session.updated = Date.now();
    commit();
    paint(session, true);
    if (session.queue.length) dequeue(session);
  }

  async function follow(session, job, initial, resumed = false, signal) {
    if (attached.has(job.id)) return;
    const path = '/api/jobs/' + job.id;
    attached.add(job.id);
    let failures = 0;
    let snapshot = initial;
    try {
      while (session.job === job && (!snapshot || snapshot.phase === 'working')) {
        await delay(snapshot ? 420 : 240);
        if (session.job !== job) return;
        try {
          snapshot = await api(path, 'GET');
          failures = 0;
        } catch (error) {
          if (++failures < (resumed ? 12 : 8)) continue;
          throw error;
        }
        if (snapshot.revision !== job.snapshot?.revision && snapshot.phase === 'working') {
          job.snapshot = snapshot;
          paint(session, true, false, true);
        }
      }
      if (session.job !== job || !snapshot) return;
      if (!resumed && snapshot.usePdfImages && !job.pdfAsImages && !signal?.aborted) {
        session.job = null;
        await launch(session, { ...job.flags, pdfAsImages: true });
      } else complete(session, job, snapshot);
    } catch (error) {
      complete(session, job, job.snapshot ?? {}, error.message);
    } finally {
      attached.delete(job.id);
      void api(path + '?discard=1', 'DELETE').catch(() => {});
    }
  }

  async function launch(session, flags = {}) {
    if (session.job) return;
    const config = { ...(flags.replayConfig ?? session.config) };
    const selected = lookup(config);
    if (!selected) {
      notify('请先选择可用的模型。');
      return;
    }
    const { provider, model } = selected;
    const options = { ...state.options };
    if (flags.skipTools) {
      try {
        if (searchRouting(config, options, provider.protocol, model.id, provider.api).builtin) config.search = false;
      } catch { config.search = false; }
      options.urlContext = options.code = false;
    }
    if (flags.skipSearch) config.search = false;
    const controller = new AbortController();
    const job = {
      id: uid(), model: model.name || model.id, config, flags, pdfAsImages: !!flags.pdfAsImages, submitted: false,
      pricing: { input: model.inputPrice, output: model.outputPrice, currency: model.currency || 'USD' },
      snapshot: { phase: 'working', status: flags.pdfAsImages ? '正在将 PDF 转为图片后重试' : '正在准备请求', text: '', reasoning: '' },
    };
    session.job = job;
    controls.set(job.id, controller);
    commit();
    paint(session, true);
    void maybeTitle(session, selected, options);
    const path = '/api/jobs/' + job.id;
    try {
      const messages = await wireHistory(session.messages, provider, model, options, flags.pdfAsImages, controller.signal, status => {
        if (job.snapshot.status !== status) { job.snapshot.status = status; paint(session, true, false, true); }
      });
      controller.signal.throwIfAborted();
      const payload = request(provider, model, config, options, messages);
      job.submitted = true;
      commit();
      const snapshot = await api(path, 'POST', payload);
      if (controller.signal.aborted) await api(path, 'DELETE');
      await follow(session, job, snapshot, false, controller.signal);
    } catch (error) {
      complete(session, job, { ...job.snapshot, phase: controller.signal.aborted ? 'cancelled' : 'error' }, controller.signal.aborted ? '' : error.message);
      if (job.submitted) void api(path + '?discard=1', 'DELETE').catch(() => {});
    } finally { controls.delete(job.id); }
  }

  function enqueue(session, message) {
    if (session.job) {
      session.queue.push(message);
      commit();
      paint(session, false);
    } else {
      session.messages.push(message);
      session.updated = Date.now();
      void launch(session);
    }
  }

  function dequeue(session) {
    if (!lookup(session.config)) { notify('有待发送消息，请先选择可用模型。'); return; }
    const message = session.queue.shift();
    if (message) enqueue(session, message);
  }

  return {
    launch, enqueue, dequeue,
    async stop(session) {
      const job = session.job;
      if (!job) return;
      controls.get(job.id)?.abort();
      if (job.submitted) await api('/api/jobs/' + job.id, 'DELETE').catch(() => {});
    },
    resume() {
      for (const session of state.sessions) {
        const job = session.job;
        if (!job) continue;
        if (job.submitted) void follow(session, job, null, true);
        else complete(session, job, job.snapshot ?? {}, '页面已刷新，附件准备被中断。请重试。');
      }
    },
  };
}
