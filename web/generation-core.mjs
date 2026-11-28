import { protocols, searchRouting } from './public/capabilities.mjs';
import { prepareRequest, upstreamError } from './protocols.mjs';
import { consume, readReply } from './stream.mjs';

export const requestLimit = 96 * 1024 * 1024;
export const deadline = 10 * 60 * 1000;
export const jobRetention = 60 * 60 * 1000;
export const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;

export function initialSnapshot() {
  return { phase: 'working', status: '正在连接', text: '', reasoning: '', sources: [], tools: [], revision: 0 };
}

export function searchQuery(messages) {
  return messages.findLast(message => message.role === 'user')?.parts
    .filter(part => part.type === 'text').map(part => part.text).join('\n').trim().slice(0, 400);
}

export function hideSecrets(value, input) {
  let message = String(value ?? '网络请求失败。');
  for (const key of [input.provider?.key, input.options?.tavilyKey]) {
    if (typeof key === 'string' && key.trim()) message = message.split(key.trim()).join('[已隐藏]');
  }
  return message;
}

export function validateInput(input) {
  if (!input || typeof input.provider?.api !== 'string' || !Object.hasOwn(protocols, input.provider.protocol)) throw new Error('请配置供应商和接口协议。');
  if (typeof input.model?.id !== 'string' || !input.model.id.trim() || input.model.id.length > 500) throw new Error('请先选择模型。');
  if (!input.config || !input.options || !Array.isArray(input.messages) || input.messages.length > 10000) throw new Error('对话参数无效。');
  if (input.messages.some(message => !['user', 'assistant'].includes(message?.role) || !Array.isArray(message.parts))) throw new Error('消息格式无效。');
  if (Number(input.model.context) < 512 || Number(input.model.max) < 1) throw new Error('请检查模型的上下文容量和输出上限。');
  if (Number(input.config.temperature) < 0 || Number(input.config.temperature) > 2 || Number(input.config.topP) < 0 || Number(input.config.topP) > 1) {
    throw new Error('Temperature 应为 0–2，Top P 应为 0–1。');
  }
}

async function tavily(input, signal, fetcher, query) {
  const { tavilyKey, tavilyCount, tavilyDepth } = input.options;
  if (!tavilyKey?.trim()) throw new Error('请在设置中填写 Tavily API Key，或关闭联网搜索。');
  if (!query) throw new Error('Tavily 搜索需要一段文字查询。');
  const response = await fetcher('https://api.tavily.com/search', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, signal, redirect: 'manual',
    body: JSON.stringify({ api_key: tavilyKey, query, max_results: Math.min(20, Math.max(1, Number(tavilyCount) || 5)),
      search_depth: tavilyDepth === 'advanced' ? 'advanced' : 'basic' }),
  });
  if (!response.ok) throw await upstreamError(response);
  const results = (await response.json()).results;
  if (!Array.isArray(results)) throw new Error('Tavily 返回了无效的搜索结果。');
  return results.filter(item => /^https?:\/\//i.test(item.url)).slice(0, 20).map(item => ({
    title: String(item.title || item.url).slice(0, 500), url: String(item.url).slice(0, 3000),
    content: String(item.content ?? '').slice(0, 2000),
  }));
}

export async function runGeneration(job, input, { fetcher = fetch, encode = JSON.stringify, query } = {}) {
  const snapshot = job.snapshot;
  const signal = AbortSignal.any([job.controller.signal, AbortSignal.timeout(deadline)]);
  function status(value) { snapshot.status = value; snapshot.revision++; }
  let prefix = '', reasoningPrefix = '';
  function update({ text, reasoning }) {
    if (prefix.length + text.length + reasoningPrefix.length + reasoning.length > 4_000_000) throw new Error('回复超过流式保存上限。');
    snapshot.text = prefix + text;
    snapshot.reasoning = reasoningPrefix + reasoning;
    snapshot.revision++;
  }
  try {
    const { provider, model, config, options } = input;
    const route = searchRouting(config, options, provider.protocol, model.id, provider.api);
    let grounding = '';
    if (route.tavily) {
      status('正在搜索 Tavily');
      let sources;
      try { sources = await tavily(input, signal, fetcher, query ?? searchQuery(input.messages)); }
      catch (error) {
        if (!signal.aborted) error.skipSearch = true;
        throw error;
      }
      snapshot.sources = sources.map(({ title, url }) => ({ title, url }));
      grounding = sources.map(source => source.title + '\n' + source.url + '\n' + source.content).join('\n\n');
    }
    const { url, headers, body, tools, omitted } = prepareRequest(input, grounding);
    snapshot.omitted = omitted;
    status(route.builtin ? '正在生成 · 内置搜索已启用' : '正在生成');
    const sendBody = async () => {
      const response = await fetcher(url, {
        method: 'POST', redirect: 'manual', signal,
        headers: { ...headers, 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
        body: encode(body),
      });
      if (!response.ok) {
        const error = await upstreamError(response, tools);
        if (!snapshot.text && (error.status === 413 || error.status === 400 && /pdf|input_file|document|file.data/i.test(error.message))) {
          error.usePdfImages = input.messages.some(message => message.parts.some(part => part.type === 'pdf'));
        }
        throw error;
      }
      return response;
    };
    let result;
    for (let round = 0; round < 4; round++) {
      const parser = consume(provider.protocol, snapshot);
      result = await readReply(await sendBody(), parser, update, signal);
      update(result);
      if (!result.done) throw new Error('供应商的流式连接提前结束。可以保留已接收的内容后重试。');
      if (provider.protocol !== 'anthropic' || result.stop !== 'pause_turn') break;
      if (round === 3) throw new Error('供应商的工具续跑次数已达上限。');
      body.messages.push({ role: 'assistant', content: result.anthropicContent.filter(Boolean) });
      prefix = snapshot.text;
      reasoningPrefix = snapshot.reasoning ? snapshot.reasoning + '\n\n' : '';
    }
    if (!snapshot.text.trim()) throw new Error('模型未返回可显示的正文。' + (result.stop ? ' 结束原因：' + result.stop : ''));
    snapshot.phase = 'done';
    status(result.stop ? '已结束 · ' + result.stop : '已完成');
  } catch (error) {
    snapshot.phase = job.controller.signal.aborted ? 'cancelled' : 'error';
    snapshot.error = snapshot.phase === 'error' ? hideSecrets(signal.aborted ? '生成已超时。' :
      error.message === 'fetch failed' ? '无法连接供应商，请检查 API 地址、网络或 TLS。' : error.message, input) : '';
    snapshot.skipTools = !snapshot.text && !!error.skipTools;
    snapshot.skipSearch = !snapshot.text && !!error.skipSearch;
    snapshot.usePdfImages = !snapshot.text && !!error.usePdfImages;
    status(snapshot.phase === 'cancelled' ? '已停止' : '请求失败');
  } finally { job.finished = Date.now(); }
}

