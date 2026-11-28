import { strFromU8, strToU8, unzipSync, zip } from '/vendor/fflate.js';

const DB = new Promise((resolve, reject) => {
  const request = indexedDB.open('aera-web', 1);
  request.onupgradeneeded = () => {
    request.result.createObjectStore('workspace');
    request.result.createObjectStore('files');
  };
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error);
});
export const uid = () => crypto.randomUUID();
const maxAttachmentBytes = 64 * 1024 * 1024;
const maxBackupBytes = 150 * 1024 * 1024;

async function storage(name, mode, action) {
  const db = await DB;
  return new Promise((resolve, reject) => {
    const tx = db.transaction(name, mode);
    const request = action(tx.objectStore(name));
    tx.oncomplete = () => resolve(request?.result);
    tx.onabort = () => reject(tx.error ?? request?.error);
    tx.onerror = () => reject(tx.error ?? request?.error);
  });
}
export const loadWorkspace = () => storage('workspace', 'readonly', store => store.get('state'));
export const saveWorkspace = value => storage('workspace', 'readwrite', store => store.put(value, 'state'));
export const readAttachment = id => storage('files', 'readonly', store => store.get(id));
export const storeAttachment = (id, file) => storage('files', 'readwrite', store => store.put(file, id));

export async function pruneAttachments(sessions) {
  const used = new Set();
  for (const session of sessions) {
    for (const file of session.draft.files) used.add(file.id);
    for (const collection of [session.messages, session.queue, ...session.branches.map(branch => branch.messages)]) {
      for (const message of collection) for (const file of message.files) used.add(file.id);
    }
  }
  await storage('files', 'readwrite', store => {
    const cursor = store.openKeyCursor();
    cursor.onsuccess = () => {
      if (!cursor.result) return;
      if (!used.has(cursor.result.key)) store.delete(cursor.result.key);
      cursor.result.continue();
    };
  });
}

export function attachmentMime(name, fallback) {
  const ext = name.toLowerCase().split('.').at(-1);
  const known = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml' };
  return known[ext] ?? (fallback || 'application/octet-stream');
}

export async function addAttachment(file) {
  if (!file.size) throw new Error(file.name + ' 是空文件。');
  if (file.size > maxAttachmentBytes) throw new Error(file.name + ' 超过 64 MiB。');
  const mime = attachmentMime(file.name, file.type);
  const descriptor = { id: uid(), name: file.name, mime, size: file.size };
  await storeAttachment(descriptor.id, new Blob([file], { type: mime }));
  return descriptor;
}

function unzipBounded(bytes, accepts, max, entryMax = max, strict = false) {
  let total = 0, count = 0;
  const names = new Set();
  const value = unzipSync(bytes, { filter: entry => {
    if (++count > 3000) throw new Error('压缩文件条目过多。');
    const { name, originalSize } = entry;
    if (names.has(name)) throw new Error('压缩文件包含重复条目。');
    names.add(name);
    if (!accepts(name)) {
      if (strict) throw new Error('备份包含无效条目。');
      return false;
    }
    total += originalSize;
    if (total > max || originalSize > (name === 'conversations.json' ? Math.min(entryMax, 32 * 1024 * 1024) : entryMax)) {
      throw new Error('压缩文件解压后过大。');
    }
    return true;
  } });
  return value;
}

function xml(data) {
  const decoded = strFromU8(data);
  if (/<!DOCTYPE|<!ENTITY/i.test(decoded)) throw new Error('Office 文档包含不受支持的 XML 声明。');
  const doc = new DOMParser().parseFromString(decoded, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('Office 文档结构有误。');
  return doc;
}

function nodes(parent, name) {
  return Array.from(parent.getElementsByTagNameNS('*', name));
}

async function documentText(blob, name) {
  const ext = name.toLowerCase().split('.').at(-1);
  let text = '';
  if (['docx', 'xlsx', 'pptx'].includes(ext)) {
    const getNames = path => /^(word\/(?:document|footnotes|endnotes)\.xml|xl\/(?:sharedStrings\.xml|worksheets\/sheet\d+\.xml)|ppt\/slides\/slide\d+\.xml)$/.test(path);
    const entries = unzipBounded(new Uint8Array(await blob.arrayBuffer()), getNames, 28 * 1024 * 1024);
    const sorted = Object.keys(entries).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (ext === 'docx') {
      text = sorted.filter(path => path.startsWith('word/')).map(path =>
        nodes(xml(entries[path]), 'p').map(p => nodes(p, 't').map(t => t.textContent).join('')).join('\n')).join('\n\n');
    } else if (ext === 'pptx') {
      text = sorted.filter(path => path.startsWith('ppt/')).map(path =>
        nodes(xml(entries[path]), 'p').map(p => nodes(p, 't').map(t => t.textContent).join('')).join('\n')).join('\n\n');
    } else {
      const strings = entries['xl/sharedStrings.xml'] ?
        nodes(xml(entries['xl/sharedStrings.xml']), 'si').map(node => nodes(node, 't').map(t => t.textContent).join('')) : [];
      text = sorted.filter(path => path.startsWith('xl/worksheets/')).map(path =>
        nodes(xml(entries[path]), 'row').map(row => nodes(row, 'c').map(cell => {
          const value = nodes(cell, 'v')[0]?.textContent ?? '';
          return cell.getAttribute('t') === 's' ? strings[Number(value)] ?? '' :
            cell.getAttribute('t') === 'inlineStr' ? nodes(cell, 't').map(t => t.textContent).join('') : value;
        }).join('\t')).join('\n')).join('\n\n');
    }
  } else if (blob.type.startsWith('text/') || /^(txt|md|csv|tsv|json|jsonl|yaml|yml|xml|svg|html|log|ini|js|ts|py|java|kt|sql|css|toml)$/.test(ext)) {
    const bytes = await blob.slice(0, 800000).arrayBuffer();
    const view = new Uint8Array(bytes);
    const encoding = view[0] === 255 && view[1] === 254 ? 'utf-16le' : view[0] === 254 && view[1] === 255 ? 'utf-16be' : 'utf-8';
    text = new TextDecoder(encoding).decode(bytes);
    if (text.includes('\0')) throw new Error(name + ' 不是可读取的文本文档。');
    if (blob.size > bytes.byteLength) text = text.slice(0, 200000) + '\n[文档内容已截断]';
  } else {
    throw new Error('不支持发送 ' + name + '。请选择图片、PDF、Office 文档或文本文件。');
  }
  if (!text.trim()) throw new Error(name + ' 未提取到可发送的文字。');
  return text.length > 200000 ? text.slice(0, 200000) + '\n[文档内容已截断]' : text;
}

function encode(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1]);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

let pdfLibrary;
export async function openPdf(blob) {
  pdfLibrary ??= import('/vendor/pdf.mjs');
  const pdf = await pdfLibrary;
  pdf.GlobalWorkerOptions.workerSrc = '/vendor/pdf.worker.mjs';
  return pdf.getDocument({
    data: new Uint8Array(await blob.arrayBuffer()), cMapUrl: '/vendor/cmaps/', cMapPacked: true,
    standardFontDataUrl: '/vendor/standard_fonts/', wasmUrl: '/vendor/wasm/', isEvalSupported: false,
  }).promise;
}

async function pdfImages(blob, signal) {
  const document = await openPdf(blob);
  const images = [];
  try {
    for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber++) {
      signal?.throwIfAborted();
      const page = await document.getPage(pageNumber);
      const size = page.getViewport({ scale: 1 });
      const viewport = page.getViewport({ scale: Math.min(2, 1400 / Math.max(size.width, size.height)) });
      const canvas = window.document.createElement('canvas');
      canvas.width = Math.ceil(viewport.width);
      canvas.height = Math.ceil(viewport.height);
      await page.render({ canvas, viewport, background: '#ffffff' }).promise;
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.79));
      images.push({ type: 'image', mime: 'image/jpeg', base64: await encode(blob) });
      canvas.width = canvas.height = 0;
    }
    return images;
  } finally { await document.destroy(); }
}

export async function attachmentParts(file, { pdfAsImages = false, ocr, signal } = {}) {
  const blob = await readAttachment(file.id);
  if (!blob) throw new Error(file.name + ' 的原始附件不可用，请重新选择。');
  if (file.mime === 'application/pdf' && (pdfAsImages || ocr)) {
    const images = await pdfImages(blob, signal);
    if (ocr) return [{ type: 'text', text: '[附件 ' + file.name + ']\n' + await ocr(file, images) }];
    return images;
  }
  if (/^image\/(jpeg|png|gif|webp)$/.test(file.mime)) {
    const part = { type: 'image', mime: file.mime, name: file.name, base64: await encode(blob) };
    if (ocr) return [{ type: 'text', text: '[附件 ' + file.name + ']\n' + await ocr(file, [part]) }];
    return [part];
  }
  if (file.mime === 'application/pdf') return [{ type: 'pdf', mime: file.mime, name: file.name, base64: await encode(blob) }];
  return [{ type: 'text', text: '[附件 ' + file.name + ']\n' + await documentText(blob, file.name) }];
}

export function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

function attachmentDescriptors(raw) {
  if (typeof raw === 'string') {
    try { raw = JSON.parse(raw); } catch { return []; }
  }
  if (Array.isArray(raw)) return raw;
  if (Array.isArray(raw?.files)) return raw.files;
  if (raw && typeof raw === 'object' && raw.mime) return [raw];
  return [];
}

export async function restoreBackup(file) {
  if (file.size > maxBackupBytes) throw new Error('备份超过 150 MiB。');
  const entries = unzipBounded(new Uint8Array(await file.arrayBuffer()),
    path => path === 'conversations.json' || /^attachments\/[A-Za-z0-9-]+$/.test(path), maxBackupBytes, maxBackupBytes, true);
  if (!entries['conversations.json']) throw new Error('缺少 conversations.json。');
  const data = JSON.parse(strFromU8(entries['conversations.json']));
  if (data.format !== 'ochat-backup' || data.version !== 1 || !Array.isArray(data.sessions) ||
      data.sessions.length > 10000 || data.sessions.some(session => !Array.isArray(session?.messages))) {
    throw new Error('不支持此 Aera 备份格式。');
  }
  const files = new Map(), pending = new Map();
  const loadMessage = message => {
    const descriptors = attachmentDescriptors(message.attachments ?? message.attachment);
    const attachments = descriptors.filter(entry => entry && typeof entry === 'object').map(entry => {
      const name = String(entry.displayName ?? '附件').slice(0, 250);
      const mime = attachmentMime(name, String(entry.mime ?? ''));
      const path = entry.backupFile;
      const bytes = Object.hasOwn(entries, path) && path?.startsWith('attachments/') ? entries[path] : null;
      let id = files.get(path);
      if (!id) {
        id = uid();
        if (bytes) {
          files.set(path, id);
          pending.set(id, new Blob([bytes], { type: mime }));
        }
      }
      return { id, name, mime, size: bytes?.length ?? 0, missing: !bytes };
    });
    return {
      id: typeof message.id === 'string' ? message.id : uid(), role: message.user ? 'user' : 'assistant',
      text: String(message.error ? '' : message.text ?? ''), reasoning: String(message.reasoningContent ?? ''),
      timestamp: Number(message.timestamp) || Date.now(), files: attachments,
      ...(message.error && { error: String(message.text ?? '请求失败。') }), cancelled: !!message.interrupted,
      model: String(message.modelName ?? message.modelId ?? ''),
      sources: (Array.isArray(message.sources) ? message.sources : []).filter(source => /^https?:\/\//i.test(source?.url)).slice(0, 60),
    };
  };
  const sessions = data.sessions.map(session => {
    const cfg = session.config ?? {};
    return {
      id: uid(), title: String(session.title || '新对话').slice(0, 140), updated: Number(session.updatedAt) || Date.now(),
      created: Number(session.createdAt) || Date.now(), pinned: !!session.pinned, archived: !!session.archived, named: true,
      config: {
        providerId: '', modelId: String(cfg.modelId ?? ''), effort: String(cfg.reasoningEffort ?? 'default'),
        system: String(cfg.systemPrompt ?? ''), temperature: Number(cfg.conversationTemperature ?? 0.7),
        topP: Number(cfg.conversationTopP ?? 0.9), max: Number(cfg.outputTokens) || null, search: !!cfg.webSearchEnabled,
      },
      messages: session.messages.filter(Boolean).map(loadMessage),
      branch: String(session.activeBranchId ?? ''),
      branches: (Array.isArray(session.branches) ? session.branches : []).filter(branch => Array.isArray(branch?.messages))
        .map(branch => ({ id: String(branch.id ?? uid()), messages: branch.messages.filter(Boolean).map(loadMessage) })),
      draft: { text: '', files: [] }, queue: [], job: null,
    };
  });
  if (pending.size) await storage('files', 'readwrite', store => {
    for (const [id, blob] of pending) store.put(blob, id);
  });
  return sessions;
}

export async function exportBackup(sessions) {
  const entries = Object.create(null);
  let total = 0, missing = 0;
  const attached = new Map();
  async function attachment(file) {
    let path = attached.get(file.id);
    if (!path && !attached.has(file.id)) {
      const blob = await readAttachment(file.id);
      if (!blob) missing++;
      else {
        total += blob.size;
        if (total > maxBackupBytes) throw new Error('附件超过 150 MiB，请分批导出。');
        path = 'attachments/' + file.id;
        entries[path] = new Uint8Array(await blob.arrayBuffer());
      }
      attached.set(file.id, path);
    }
    return { uri: 'aera-web:' + file.id, displayName: file.name, mime: file.mime, type: file.mime.startsWith('image/') ? 'image' : 'document',
      ...(path ? { backupFile: path } : { backupUnavailable: true }) };
  }
  const project = async messages => {
    const out = [];
    for (const message of messages) {
      const descriptors = [];
      for (const file of message.files) descriptors.push(await attachment(file));
      out.push({
        id: message.id, text: message.error || message.text, user: message.role === 'user', timestamp: message.timestamp,
        reasoningContent: message.reasoning, modelName: message.model, error: !!message.error, interrupted: !!message.cancelled,
        sources: message.sources ?? [], ...(descriptors.length ? { attachments: descriptors } : {}),
      });
    }
    return out;
  };
  const payload = [];
  for (const session of sessions) {
    const branch = [];
    for (const item of session.branches) branch.push({ id: item.id,
      messages: await project(item.id === session.branch ? session.messages : item.messages) });
    payload.push({
      id: session.id, title: session.title, pinned: session.pinned, archived: session.archived, titleManual: session.named,
      updatedAt: session.updated, createdAt: session.created,
      messages: await project(session.messages),
      ...(branch.length && { activeBranchId: session.branch, branches: branch }),
      config: { modelId: session.config.modelId, reasoningEffort: session.config.effort, systemPrompt: session.config.system,
        conversationTemperature: session.config.temperature, conversationTopP: session.config.topP,
        outputTokens: session.config.max ?? '', webSearchEnabled: session.config.search },
    });
  }
  entries['conversations.json'] = strToU8(JSON.stringify({ format: 'ochat-backup', version: 1, createdAt: Date.now(), sessions: payload }));
  if (entries['conversations.json'].length > 32 * 1024 * 1024 || total + entries['conversations.json'].length > maxBackupBytes) {
    throw new Error('备份数据过大，请分批导出。');
  }
  const bytes = await new Promise((resolve, reject) => zip(entries, { level: 1 }, (error, data) => error ? reject(error) : resolve(data)));
  return { blob: new Blob([bytes], { type: 'application/zip' }), missing };
}
