import { requestLimit, validateInput } from '../generation-core.mjs';

const encoder = new TextEncoder();
const blockSize = 1024 * 1024;
const manifestLimit = 4 * blockSize;
const maxReferences = 10000;
const controlCharacter = /[\u0000-\u001f"\\]/g;

async function readManifest(reader) {
  const chunks = [];
  let length = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) throw new Error('请求缺少头部。');
    const newline = value.indexOf(10);
    const header = newline < 0 ? value : value.subarray(0, newline);
    length += header.byteLength;
    if (length > manifestLimit) throw new Error('对话元数据超过 4 MiB，请减少本次对话历史。');
    chunks.push(header);
    if (newline < 0) continue;
    const manifest = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { manifest.set(chunk, offset); offset += chunk.byteLength; }
    return { manifest: JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(manifest)),
      length: length + 1, remaining: value.subarray(newline + 1) };
  }
}

function normalize(manifest, length) {
  const { version, input, query, references } = manifest ?? {};
  if (version !== 1 || !Array.isArray(references) || references.length > maxReferences ||
      typeof query !== 'string' || query.length > 400) throw new Error('请求头部格式无效。');
  validateInput(input);
  if (references.some(ref => !ref || !Number.isSafeInteger(ref.size) || ref.size < 1 ||
      (ref.format === 'base64' ? ref.size % 4 !== 0 : ref.format !== 'string' ||
        !Number.isSafeInteger(ref.bytes) || ref.bytes < 1 || ref.bytes > ref.size))) {
    throw new Error('请求数据引用无效。');
  }
  if (length + references.reduce((sum, ref) => sum + ref.size, 0) > requestLimit) throw new Error('请求超过 96 MiB，请减少附件后重试。');

  const marker = 'AeraPayload' + crypto.randomUUID().replaceAll('-', '') + 'Ref';
  const used = new Set();
  function resolve(id, format) {
    if (!Number.isSafeInteger(id) || used.has(id) || references[id]?.format !== format) throw new Error('请求数据引用无效。');
    used.add(id);
    return marker + id + 'End';
  }
  input.messages = input.messages.map(message => {
    const { role, parts, reasoningRef } = message;
    const converted = parts.map(part => {
      if (part?.type === 'text') {
        if (part.textRef !== undefined) return { type: 'text', text: resolve(part.textRef, 'string'), textBytes: references[part.textRef].bytes };
        if (typeof part.text === 'string') return { type: 'text', text: part.text };
      } else if (['image', 'pdf'].includes(part?.type) && typeof part.mime === 'string') {
        return { type: part.type, mime: part.mime, name: part.name, base64: resolve(part.fileRef, 'base64') };
      }
      throw new Error('消息内容块无效。');
    });
    if (reasoningRef !== undefined) return { role, parts: converted, reasoning: resolve(reasoningRef, 'string'),
      reasoningBytes: references[reasoningRef].bytes };
    return { role, parts: converted, ...(typeof message.reasoning === 'string' && { reasoning: message.reasoning }) };
  });
  if (used.size !== references.length) throw new Error('请求中包含未引用的数据。');
  return { input, query, references, marker };
}

function validateBlock(format) {
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  let tail = '';
  return (bytes, last) => {
    const text = tail + decoder.decode(bytes, { stream: !last });
    tail = '';
    if (format === 'base64') {
      if (!(last ? /^[A-Za-z0-9+/]+={0,2}$/ : /^[A-Za-z0-9+/]+$/).test(text)) throw new Error('附件的 Base64 数据无效。');
      return;
    }
    controlCharacter.lastIndex = 0;
    for (let match = controlCharacter.exec(text); match; match = controlCharacter.exec(text)) {
      const index = match.index;
      if (match[0] !== '\\') throw new Error('对话文本编码无效。');
      const kind = text[index + 1];
      const width = kind === 'u' ? 6 : 2;
      if (index + width > text.length && !last) { tail = text.slice(index); return; }
      if (kind === 'u' ? !/^[\da-f]{4}$/i.test(text.slice(index + 2, index + 6)) :
        kind === undefined || !'"\\/bfnrt'.includes(kind)) throw new Error('对话文本编码无效。');
      controlCharacter.lastIndex = index + width;
    }
  };
}

export async function receivePayload(request, validateProvider, writeBlock) {
  if (request.headers.get('Content-Type') !== 'application/x-aera-job' || !request.body) throw new Error('请求格式无效，请刷新页面后重试。');
  if (Number(request.headers.get('Content-Length')) > requestLimit) throw new Error('请求超过 96 MiB，请减少附件后重试。');
  const reader = request.body.getReader();
  try {
    const { manifest, length, remaining } = await readManifest(reader);
    const job = normalize(manifest, length);
    validateProvider(job.input.provider);
    let chunk = remaining;
    for (const [id, ref] of job.references.entries()) {
      const check = validateBlock(ref.format);
      for (let start = 0, block = 0; start < ref.size; start += blockSize, block++) {
        const data = new Uint8Array(Math.min(blockSize, ref.size - start));
        for (let offset = 0; offset < data.byteLength;) {
          if (!chunk.byteLength) {
            const next = await reader.read();
            if (next.done) throw new Error('请求数据不完整。');
            chunk = next.value;
            continue;
          }
          const count = Math.min(chunk.byteLength, data.byteLength - offset);
          data.set(chunk.subarray(0, count), offset);
          chunk = chunk.subarray(count);
          offset += count;
        }
        check(data, start + data.byteLength === ref.size);
        writeBlock(id, block, data.buffer);
      }
    }
    while (!chunk.byteLength) {
      const next = await reader.read();
      if (next.done) return job;
      chunk = next.value;
    }
    throw new Error('请求含有多余数据。');
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
}

export function payloadBody(body, job, readBlock) {
  const json = JSON.stringify(body);
  const pattern = new RegExp(job.marker + '(\\d+)End', 'g');
  const pieces = [];
  let total = 0, index = 0;
  for (const match of json.matchAll(pattern)) {
    const text = encoder.encode(json.slice(index, match.index));
    const id = Number(match[1]);
    const size = job.references[id].size;
    pieces.push(text, id);
    total += text.byteLength + size;
    index = match.index + match[0].length;
  }
  if (!pieces.length) return json;
  const tail = encoder.encode(json.slice(index));
  pieces.push(tail);
  const { readable, writable } = new FixedLengthStream(total + tail.byteLength);
  async function* bytes() {
    for (const piece of pieces) {
      if (typeof piece !== 'number') { if (piece.byteLength) yield piece; continue; }
      const blocks = Math.ceil(job.references[piece].size / blockSize);
      for (let i = 0; i < blocks; i++) yield new Uint8Array(readBlock(piece, i));
    }
  }
  const iterator = bytes();
  const stream = new ReadableStream({
    async pull(controller) {
      const next = await iterator.next();
      if (next.done) controller.close();
      else controller.enqueue(next.value);
    },
    cancel() { return iterator.return(); },
  });
  void stream.pipeTo(writable).catch(() => {});
  return readable;
}
