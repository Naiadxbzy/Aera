export async function* readEvents(body) {
  const decoder = new TextDecoder();
  let pending = '', event = '', data = [];
  for await (const chunk of body) {
    pending += decoder.decode(chunk, { stream: true });
    if (pending.length > 8_000_000) throw new Error('供应商事件过大。');
    let end;
    while ((end = pending.indexOf('\n')) !== -1) {
      const line = pending.slice(0, end).replace(/\r$/, '');
      pending = pending.slice(end + 1);
      if (!line) {
        if (data.length) yield { event, value: data.join('\n') };
        event = ''; data = [];
      } else if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
  }
  pending += decoder.decode();
  if (pending.startsWith('data:')) data.push(pending.slice(5).trim());
  if (data.length) yield { event, value: data.join('\n') };
}

function scanReferences(value, append, depth = 0) {
  if (!value || typeof value !== 'object' || depth > 12) return;
  if (Array.isArray(value)) {
    for (const item of value) scanReferences(item, append, depth + 1);
    return;
  }
  if (typeof (value.url ?? value.uri) === 'string') append(value.title ?? value.document_title ?? '', value.url ?? value.uri);
  for (const key of ['annotations', 'url_citation', 'sources', 'action', 'citations', 'groundingChunks', 'web', 'content', 'output', 'result', 'url_context_metadata', 'urlMetadata']) {
    scanReferences(value[key], append, depth + 1);
  }
}

export function consume(protocol, snapshot) {
  const parts = new Map(), thoughts = new Map(), sequences = new Set();
  const items = new Map();
  let stop = '', done = false;
  const anthropicContent = [];
  const source = (title, url) => {
    if (!/^https?:\/\//i.test(url) || snapshot.sources.some(entry => entry.url === url) || snapshot.sources.length >= 60) return;
    snapshot.sources.push({ title: String(title || url).slice(0, 500), url: url.slice(0, 3000) });
  };
  function tool(name, status, detail) {
    const text = detail ? String(detail).slice(0, 3500) : '';
    const previous = snapshot.tools.at(-1);
    if (previous?.name === name && previous.status === status && previous.detail === text) return;
    if (snapshot.tools.length < 60) snapshot.tools.push({ name, status, detail: text });
  }
  function responseItem(item, i) {
    scanReferences(item, source);
    for (const [j, part] of (item?.content ?? []).entries()) {
      if (part.type === 'output_text') parts.set(i * 10000 + j, part.text ?? '');
      else if (part.type === 'refusal') parts.set(i * 10000 + j, part.refusal ?? '');
    }
    for (const [j, part] of (item?.summary ?? []).entries()) {
      if (part.text) thoughts.set(i * 10000 + j, part.text);
    }
  }
  function responses(data, type) {
    if (data.sequence_number !== undefined) {
      if (sequences.has(data.sequence_number)) return;
      sequences.add(data.sequence_number);
    }
    const itemId = data.item_id ?? data.item?.id;
    const i = data.output_index ?? items.get(itemId) ?? 0;
    if (itemId) items.set(itemId, i);
    const k = i * 10000 + (data.content_index ?? 0), reason = i * 10000 + (data.summary_index ?? 0);
    if (type === 'response.output_text.delta' || type === 'response.refusal.delta') {
      parts.set(k, (parts.get(k) ?? '') + (data.delta ?? ''));
    } else if (type === 'response.output_text.done' || type === 'response.refusal.done') {
      parts.set(k, data.text ?? data.refusal ?? parts.get(k) ?? '');
    } else if (type === 'response.reasoning_summary_text.delta') {
      thoughts.set(reason, (thoughts.get(reason) ?? '') + (data.delta ?? ''));
    } else if (type === 'response.reasoning_summary_text.done' || type === 'response.reasoning_summary_part.done') {
      thoughts.set(reason, data.text ?? data.part?.text ?? thoughts.get(reason) ?? '');
    } else if (type === 'response.output_item.done') responseItem(data.item, i);
    else if (['response.completed', 'response.incomplete', ''].includes(type)) {
      const response = data.response ?? data;
      if (response.error) throw new Error(response.error.message ?? '生成失败。');
      if (typeof response.output_text === 'string' && !response.output?.length) parts.set(0, response.output_text);
      for (const [index, item] of (response.output ?? []).entries()) responseItem(item, index);
      if (response.usage) snapshot.usage = response.usage;
      stop = response.incomplete_details?.reason ?? '';
      done = true;
    }
    scanReferences(data.annotation ?? data.item ?? data.response, source);
    const call = type.match(/^response\.(web_search_call|code_interpreter_call)\.(.+)$/);
    if (call) tool(call[1], call[2], data.code);
  }
  function anthropic(data, type) {
    if (!type || type === 'message') {
      for (const [index, block] of (data.content ?? []).entries()) anthropic({ index, content_block: block }, 'content_block_start');
      snapshot.usage = data.usage ?? snapshot.usage;
      stop = data.stop_reason ?? '';
      done = true;
      return;
    }
    if (type === 'message_start') snapshot.usage = data.message?.usage ?? snapshot.usage;
    if (type === 'message_delta') {
      stop = data.delta?.stop_reason ?? stop;
      if (data.usage) snapshot.usage = { ...snapshot.usage, ...data.usage };
    }
    if (type === 'message_stop') done = true;
    const index = data.index ?? 0;
    if (type === 'content_block_start') {
      const block = structuredClone(data.content_block ?? {});
      anthropicContent[index] = block;
      if (block.type === 'text') parts.set(index, block.text ?? '');
      else if (block.type === 'thinking') thoughts.set(index, block.thinking ?? '');
      else if (block.type === 'server_tool_use') tool(block.name, '调用');
      else if (block.type?.includes('tool_result')) tool(block.type, '完成', JSON.stringify(block.content ?? ''));
      scanReferences(block, source);
    } else if (type === 'content_block_delta') {
      const delta = data.delta ?? {}, block = anthropicContent[index] ?? {};
      if (delta.type === 'thinking_delta') {
        block.thinking = (thoughts.get(index) ?? '') + (delta.thinking ?? '');
        thoughts.set(index, block.thinking);
      } else if (delta.type === 'signature_delta') block.signature = (block.signature ?? '') + (delta.signature ?? '');
      else if (delta.type === 'text_delta') {
        block.text = (parts.get(index) ?? '') + (delta.text ?? '');
        parts.set(index, block.text);
      } else if (delta.type === 'input_json_delta') block.partial = (block.partial ?? '') + (delta.partial_json ?? '');
      else if (delta.citation) {
        (block.citations ??= []).push(delta.citation);
        scanReferences(delta.citation, source);
      }
    } else if (type === 'content_block_stop') {
      const block = anthropicContent[index];
      if (block?.partial) {
        try { block.input = JSON.parse(block.partial); } catch { /* Provider sent a partial tool input. */ }
        delete block.partial;
        if (block.name) tool(block.name, '输入', JSON.stringify(block.input ?? {}));
      }
    }
  }
  function gemini(data) {
    for (const candidate of (data.candidates ?? []).slice(0, 1)) {
      for (const part of candidate.content?.parts ?? []) {
        if (typeof part.text === 'string') {
          const map = part.thought ? thoughts : parts;
          map.set(0, (map.get(0) ?? '') + part.text);
        }
        if (part.executableCode) tool('codeExecution', '调用', part.executableCode.code);
        if (part.codeExecutionResult) tool('codeExecution', part.codeExecutionResult.outcome ?? '完成', part.codeExecutionResult.output);
      }
      scanReferences(candidate.groundingMetadata, source);
      for (const meta of candidate.urlContextMetadata?.urlMetadata ?? []) if (meta.retrievedUrl) source('', meta.retrievedUrl);
      if (candidate.finishReason) {
        stop = candidate.finishReason === 'STOP' ? '' : candidate.finishReason;
        done = true;
      }
    }
    if (data.usageMetadata) snapshot.usage = data.usageMetadata;
    if (data.promptFeedback?.blockReason) throw new Error('模型拒绝请求：' + data.promptFeedback.blockReason);
  }
  function chat(data) {
    const choice = data.choices?.[0] ?? {};
    const delta = choice.delta ?? choice.message ?? {};
    if (delta.content || delta.refusal) parts.set(0, (parts.get(0) ?? '') + (delta.content ?? delta.refusal));
    const reasoning = delta.reasoning_content ?? delta.reasoning ?? delta.thinking;
    if (typeof reasoning === 'string') thoughts.set(0, (thoughts.get(0) ?? '') + reasoning);
    scanReferences(delta, source);
    if (data.usage) snapshot.usage = data.usage;
    if (choice.finish_reason) {
      stop = choice.finish_reason === 'stop' ? '' : choice.finish_reason;
      done = true;
    }
  }
  return {
    event(data, name = '') {
      const type = data.type ?? name;
      if (type === 'error' || type === 'response.failed' || data.error) {
        throw new Error(data.error?.message ?? data.response?.error?.message ?? data.message ?? '供应商返回生成错误。');
      }
      if (protocol === 'responses') responses(data, type);
      else if (protocol === 'anthropic') anthropic(data, type);
      else if (protocol === 'gemini') gemini(data);
      else chat(data);
      return this.result();
    },
    done() { done = true; },
    result() {
      const sorted = (map, sep) => [...map.keys()].sort((a, b) => a - b).map(key => map.get(key)).join(sep);
      return { text: sorted(parts, ''), reasoning: sorted(thoughts, '\n\n'), stop, done, anthropicContent };
    },
  };
}

export async function readReply(response, parser, onUpdate, signal) {
  if ((response.headers.get('content-type') ?? '').includes('text/event-stream')) {
    for await (const { event, value } of readEvents(response.body)) {
      signal.throwIfAborted();
      if (value === '[DONE]') { parser.done(); break; }
      let payload;
      try { payload = JSON.parse(value); } catch { throw new Error('供应商返回了无效的流式数据。'); }
      onUpdate(parser.event(payload, event));
    }
  } else {
    const payload = await response.json().catch(() => { throw new Error('接口未返回 SSE 或有效 JSON，请检查地址与协议。'); });
    for (const item of Array.isArray(payload) ? payload : [payload]) onUpdate(parser.event(item));
    parser.done();
  }
  return parser.result();
}
