import { capabilities } from './capabilities.mjs';
import { renderReasoningPopover, reasoningLabel } from './reasoning.mjs';
import { hostedJobBody } from './cloud-transport.mjs';
import { hosted } from './runtime.mjs';
import { addAttachment, download, exportBackup, loadWorkspace, pruneAttachments, readAttachment, restoreBackup, saveWorkspace, uid } from './files.mjs';
import { createGeneration } from './generation.mjs';
import { actions, control, createSettings, openDialog, row } from './settings.mjs';
import { button, el, icon, markdown, mountIcons, safeUrl, staticPreview } from './ui.mjs';

const $ = selector => document.querySelector(selector);
const prompt = $('#prompt'), scroll = $('#chat-scroll'), messages = $('#messages'), dialog = $('#dialog');
const effortButton = $('#effort'), reasoningPopover = $('#reasoning-popover');
const defaults = {
  searchMode: 'automatic', tavilyCount: 5, tavilyDepth: 'basic', urlContext: false, code: false,
  theme: 'system', accent: '', welcomeTitle: '', welcomeCopy: '', reasoningColorMode: 'theme', reasoningCustomColor: '#4089f6',
  pdfOcr: '', imageOcr: '',
  autoTitle: false, titleModel: '', titlePrompt: '根据用户的第一条消息生成简洁的对话标题。只返回标题，最多 20 个汉字。',
};
const configDefaults = { providerId: '', modelId: '', effort: 'default', system: '', temperature: 0.7, topP: 0.9, max: null, search: false };
const state = { sessions: [], providers: [], options: { ...defaults }, active: '', remembered: [], keys: {} };
let persistenceTimer, noticeTimer, archive = false, pageLimit = 36, renderedSession, renderedMessages, renderedLimit;
const storedOwner = localStorage.getItem('aera-session');
const owner = storedOwner || uid();
if (!storedOwner) localStorage.setItem('aera-session', owner);

function notify(text) {
  const notice = $('#notice');
  notice.textContent = text;
  notice.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.hidden = true; }, 4200);
}

function commit(debounce = false) {
  clearTimeout(persistenceTimer);
  const save = () => {
    const { sessions, providers, options, active } = state;
    void saveWorkspace({ sessions, providers, options, active }).catch(() => notify('浏览器存储空间不足或不可用。请导出备份。'));
  };
  if (debounce) persistenceTimer = setTimeout(save, 180);
  else save();
}

function setKey(id, key, remember) {
  if (key) state.keys[id] = key;
  else delete state.keys[id];
  state.remembered = state.remembered.filter(item => item !== id);
  if (key && remember) state.remembered.push(id);
  const retained = Object.fromEntries(state.remembered.map(item => [item, state.keys[item]]));
  const temporary = Object.fromEntries(Object.entries(state.keys).filter(([id]) => !state.remembered.includes(id)));
  localStorage.setItem('aera-remembered-keys', JSON.stringify(retained));
  sessionStorage.setItem('aera-tab-keys', JSON.stringify(temporary));
}

async function api(path, method = 'GET', body, signal) {
  const hostedJob = hosted && method === 'POST' && path.startsWith('/api/jobs/');
  const data = hostedJob ? hostedJobBody(body) : body === undefined ? undefined : JSON.stringify(body);
  const headers = { 'X-Aera-Session': owner, ...(data !== undefined && {
    'Content-Type': hostedJob ? data.type : 'application/json',
  }) };
  let response;
  try { response = await fetch(path, { method, headers, body: data, signal }); }
  catch (error) {
    signal?.throwIfAborted();
    throw new Error(hosted ? '无法连接 Aera 云端生成服务。' : '无法连接 Aera 本地服务。请检查服务是否仍在运行。', { cause: error });
  }
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || '请求失败，HTTP ' + response.status);
  return payload;
}

const current = () => state.sessions.find(session => session.id === state.active);
function lookup(config) {
  const provider = state.providers.find(item => item.id === config.providerId);
  const model = provider?.models.find(item => item.id === config.modelId);
  return model ? { provider, model } : null;
}
const currentModel = () => lookup(current().config);

function selectModel(provider, model) {
  const session = current();
  Object.assign(session.config, { providerId: provider.id, modelId: model.id, effort: 'default', max: null });
  commit();
  paint(session);
  if (session.queue.length && !session.job) generation.dequeue(session);
}

function newSession() {
  const previous = current();
  const config = { ...configDefaults, ...previous?.config, effort: 'default' };
  const empty = state.sessions.find(session => !session.messages.length && !session.job && !session.queue.length &&
    !session.draft.text && !session.draft.files.length && !session.named && !session.archived);
  if (empty) {
    empty.config = config;
    return empty;
  }
  return { id: uid(), title: '新对话', created: Date.now(), updated: Date.now(), config,
    messages: [], draft: { text: '', files: [] }, queue: [], job: null, branch: '', branches: [] };
}

function switchSession(session, focus = false) {
  if (reasoningPopover.matches(':popover-open')) reasoningPopover.hidePopover();
  if (!state.sessions.includes(session)) state.sessions.push(session);
  state.active = session.id;
  if (!lookup(session.config) && session.config.modelId) {
    for (const provider of state.providers) {
      if (provider.models.some(model => model.id === session.config.modelId)) {
        session.config.providerId = provider.id;
        break;
      }
    }
  }
  pageLimit = 36;
  $('#sidebar').classList.remove('open');
  $('.scrim').hidden = true;
  $('.mobile-menu').setAttribute('aria-expanded', 'false');
  renderedSession = null;
  commit();
  paint(session, true, true);
  if (focus) prompt.focus();
}

function updateTheme() {
  const { accent, theme } = state.options;
  const root = document.documentElement;
  const dark = theme === 'dark' || theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches;
  root.dataset.theme = dark ? 'dark' : 'light';
  if (/^#[0-9a-f]{6}$/i.test(accent)) {
    const channels = accent.slice(1).match(/../g).map(hex => parseInt(hex, 16) / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    const luminance = channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
    root.style.setProperty('--accent-button', accent);
    root.style.setProperty('--on-accent', luminance > .18 ? '#141526' : '#fff');
  } else {
    root.style.removeProperty('--accent-button');
    root.style.removeProperty('--on-accent');
  }
  $('.theme-switch').innerHTML = icon(dark ? 'sun' : 'moon');
  $('#welcome-title').textContent = state.options.welcomeTitle || '今天想聊些什么？';
  const welcomeCopy = $('#welcome-copy');
  welcomeCopy.textContent = state.options.welcomeCopy;
  welcomeCopy.hidden = !state.options.welcomeCopy;
}

const generation = createGeneration({ state, api, lookup, commit, paint, notify });
const { settings, models } = createSettings({
  state, api, currentModel, selectModel, notify, setKey,
  commit() { commit(); updateTheme(); paint(current()); },
  async exportData() {
    const sessions = state.sessions.filter(session => session.messages.length || session.branches.length || session.named);
    if (!sessions.length) return notify('还没有可导出的对话。');
    try {
      const result = await exportBackup(sessions);
      download(result.blob, 'Aera-' + new Date().toISOString().slice(0, 10) + '.zip');
      notify(result.missing ? '备份已生成，' + result.missing + ' 个原始附件不可用。' : '对话备份已生成');
    } catch (error) { notify(error.message); }
  },
  importData() { $('#backup').click(); },
});

function renderHistory() {
  const list = $('#sessions');
  const originalTop = list.scrollTop;
  const query = $('#history-search').value.trim().toLowerCase();
  $('#history-title').textContent = archive ? '已归档' : '历史对话';
  const toggle = $('#archive');
  toggle.setAttribute('aria-pressed', archive);
  toggle.title = toggle.ariaLabel = archive ? '返回历史对话' : '查看归档';
  const sessions = state.sessions.filter(session => !!session.archived === archive &&
    (session.messages.length || session.named || session.job || session.draft.text || session.draft.files.length) &&
    (!query || session.title.toLowerCase().includes(query) || session.messages.some(message => message.text.toLowerCase().includes(query) ||
      message.files.some(file => file.name.toLowerCase().includes(query)))));
  sessions.sort((a, b) => Number(b.pinned ?? false) - Number(a.pinned ?? false) || b.updated - a.updated);
  list.replaceChildren();
  if (!sessions.length) {
    const empty = el('div', undefined, 'history-empty');
    empty.innerHTML = icon(query ? 'search' : 'chat');
    empty.append(el('span', query ? '没有找到匹配的对话。' : archive ? '暂无归档对话' : '暂无历史对话'));
    list.append(empty);
  }
  let previous = '';
  for (const session of sessions) {
    const days = Math.floor((Date.now() - new Date(session.updated).setHours(0, 0, 0, 0)) / 86400000);
    const group = session.pinned ? '已置顶' : days === 0 ? '今天' : days === 1 ? '昨天' : days < 7 ? '最近 7 天' : '更早';
    if (group !== previous) { list.append(el('div', group, 'history-group')); previous = group; }
    const line = el('div', undefined, 'history-row' + (session.id === state.active ? ' active' : ''));
    const open = button('', 'session', session.id, '', session.pinned ? 'pin' : 'chat');
    open.append(el('span', session.title));
    if (session.id === state.active) open.setAttribute('aria-current', 'page');
    open.title = session.title + (session.job ? ' · 正在生成' : '');
    const more = button('', 'chat-menu', session.id, 'plain-icon', 'more');
    more.ariaLabel = '对话操作 · ' + session.title;
    line.append(open, more);
    list.append(line);
  }
  list.scrollTop = originalTop;
}

function fileChip(file, removable = false) {
  const chip = el('span', undefined, 'file-chip');
  const view = button(file.name + (file.missing ? '（不可用）' : ''), 'file', file.id, 'small-button', 'file');
  view.title = file.name;
  view.file = file;
  chip.append(view);
  if (removable) {
    const remove = button('', 'remove-file', file.id, 'small-button remove-file', 'close');
    remove.ariaLabel = '移除 ' + file.name;
    chip.append(remove);
  }
  return chip;
}

function resizeComposer() {
  prompt.style.height = 'auto';
  prompt.style.height = Math.min(prompt.scrollHeight, 180) + 'px';
}

function renderEffort(value) {
  const label = reasoningLabel(value);
  $('#effort-label').textContent = label;
  effortButton.ariaLabel = effortButton.title = '思考强度 · ' + label;
  effortButton.classList.toggle('is-manual', value !== 'default');
}

function renderComposer(session) {
  const tray = $('#draft-files');
  tray.replaceChildren(...session.draft.files.map(file => fileChip(file, true)));
  tray.hidden = !session.draft.files.length;
  if (prompt.value !== session.draft.text) {
    prompt.value = session.draft.text;
    resizeComposer();
  }
  const selected = lookup(session.config);
  const modelLabel = $('#top-model-label');
  modelLabel.textContent = selected ? selected.model.name || selected.model.id : '选择模型';
  const caps = selected ? capabilities(selected.model, selected.provider.protocol) : null;
  const effort = caps?.levels.includes(session.config.effort) ? session.config.effort : 'default';
  renderEffort(effort);
  $('#web-search').setAttribute('aria-pressed', !!session.config.search);
  $('#stop').hidden = !session.job;
  $('.send').title = session.job ? '加入待发送队列 (Enter)' : '发送 (Enter)';
  $('.send').ariaLabel = session.job ? '加入待发送队列' : '发送消息';
  $('.send').disabled = !(session.draft.text.trim() || session.draft.files.length);
  const queue = $('#queue');
  queue.hidden = !session.queue.length;
  queue.replaceChildren();
  if (session.queue.length) {
    queue.append(el('span', session.queue.length + ' 条消息待发送'));
    for (const item of session.queue) {
      const remove = button((item.text.trim() || item.files[0]?.name || '附件').slice(0, 26), 'remove-queued', item.id, 'small-button', 'close');
      remove.title = '从队列移除';
      queue.append(remove);
    }
    if (!session.job) queue.append(button('继续发送', 'send-queued'));
  }
}

function details(label, content, className) {
  const pane = el('details', undefined, className);
  pane.append(el('summary', label), content);
  return pane;
}

function renderMessage(message, streaming = false) {
  const node = el('article', undefined, 'message ' + message.role);
  const user = message.role === 'user';
  const avatar = el('div', undefined, 'avatar ' + (user ? 'user-avatar' : 'assistant-avatar'));
  avatar.innerHTML = icon(user ? 'user' : 'spark');
  const content = el('div', undefined, 'message-body');
  const meta = el('div', undefined, 'message-meta');
  meta.append(el('strong', user ? '你' : 'Aera'));
  if (message.model) meta.append(el('span', message.model));
  if (!streaming && message.timestamp) meta.append(el('time', new Date(message.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })));
  content.append(meta);
  if (message.omitted) content.append(el('div', '已按上下文预算略过 ' + message.omitted + ' 条较早的消息', 'message-meta'));
  if (streaming || message.cancelled) {
    const status = el('div', undefined, 'status-card');
    status.innerHTML = icon(streaming ? 'spark' : 'stop');
    status.append(el('span', message.status || (streaming ? '正在生成' : '已停止')));
    content.append(status);
  }
  if (message.files?.length) {
    const files = el('div', undefined, 'file-row');
    files.append(...message.files.map(file => fileChip(file)));
    content.append(files);
  }
  if (message.reasoning) content.append(details('思考内容', markdown(message.reasoning), 'reasoning-details'));
  if (message.text) content.append(user ? el('div', message.text, 'user-text') : markdown(message.text));
  if (message.sources?.length) {
    const list = el('ol');
    for (const source of message.sources) {
      const url = safeUrl(source.url);
      if (!url) continue;
      const anchor = el('a', source.title || url);
      anchor.href = url; anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
      const item = el('li'); item.append(anchor); list.append(item);
    }
    if (list.children.length) content.append(details('联网来源 · ' + list.children.length, list, 'source-details'));
  }
  if (message.error) {
    const card = el('div', undefined, 'status-card error');
    card.append(details('请求失败 · 查看详情', el('pre', message.error)));
    if (message.skipTools) card.append(button('跳过内置工具重试', 'retry-tools', message.id, 'small-button', 'cycle'));
    if (message.skipSearch) card.append(button('跳过联网搜索重试', 'retry-search', message.id, 'small-button', 'cycle'));
    content.append(card);
  }
  if (!streaming) {
    const tools = el('div', undefined, 'message-actions');
    if (message.text) tools.append(button('复制', 'copy', message.id, 'small-button', 'copy'));
    if (user) tools.append(button('编辑', 'edit', message.id, 'small-button', 'pen'));
    else tools.append(button('重新生成', 'retry', message.id, 'small-button', 'cycle'));
    if (message.tools?.length || message.usage || message.status) tools.append(button('详情', 'message-details', message.id, 'small-button'));
    content.append(tools);
  }
  node.append(avatar, content);
  return node;
}

function renderMessages(session, follow, force = false) {
  const nearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 125;
  const same = renderedSession === session.id;
  const needsFull = !same || renderedLimit !== pageLimit || renderedMessages !== session.messages.length || force;
  const empty = !session.messages.length && !session.job;
  $('.workspace').classList.toggle('is-empty', empty);
  $('#welcome').hidden = !empty;
  messages.hidden = empty;
  if (needsFull) {
    messages.replaceChildren();
    if (session.branches.length > 1) {
      const bar = el('div', undefined, 'queue');
      const index = session.branches.findIndex(branch => branch.id === session.branch);
      bar.append(el('span', '对话分支 ' + (index + 1) + ' / ' + session.branches.length));
      const previous = button('上一分支', 'branch', String(index - 1)), next = button('下一分支', 'branch', String(index + 1));
      previous.disabled = index <= 0 || !!session.job || !!session.queue.length;
      next.disabled = index + 1 >= session.branches.length || !!session.job || !!session.queue.length;
      bar.append(previous, next);
      messages.append(bar);
    }
    if (session.messages.length > pageLimit) messages.append(button('加载较早消息', 'older', undefined, 'small-button', 'archive'));
    for (const message of session.messages.slice(-pageLimit)) messages.append(renderMessage(message));
  }
  const previousStream = messages.querySelector('.live-message');
  if (session.job) {
    const node = renderMessage({ ...session.job.snapshot, model: session.job.model, role: 'assistant' }, true);
    node.classList.add('live-message');
    for (const open of previousStream?.querySelectorAll('details[open]') ?? []) {
      const className = [...open.classList][0];
      if (className) node.querySelector('.' + className)?.setAttribute('open', '');
    }
    if (previousStream) previousStream.replaceWith(node);
    else messages.append(node);
  } else previousStream?.remove();
  renderedSession = session.id;
  renderedLimit = pageLimit;
  renderedMessages = session.messages.length;
  if (!same || (follow && nearBottom)) scroll.scrollTop = scroll.scrollHeight;
}

function paint(session, follow = false, force = false, streaming = false) {
  if (!streaming) renderHistory();
  if (session.id !== state.active) return;
  $('#chat-title').textContent = session.title;
  document.title = session.title + ' · Aera';
  if (!streaming) renderComposer(session);
  renderMessages(session, follow, force);
}

function sendMessage() {
  const session = current();
  const { text, files } = session.draft;
  if (!text.trim() && !files.length) return;
  if (!lookup(session.config)) {
    notify('先配置并选择一个模型，草稿已保留。');
    models();
    return;
  }
  const item = { id: uid(), role: 'user', text: text.trim(), files: [...files], timestamp: Date.now() };
  session.draft = { text: '', files: [] };
  if (!session.named && !session.messages.some(message => message.role === 'user')) {
    session.title = (item.text.replace(/\s+/g, ' ') || item.files[0]?.name || '附件对话').slice(0, 45);
  }
  generation.enqueue(session, item);
  scroll.scrollTop = scroll.scrollHeight;
  prompt.focus();
}

function branch(session, index, text, flags) {
  if (session.job || session.queue.length) { notify('请等当前生成和消息队列处理完毕后再切换分支。'); return; }
  if (!lookup(flags?.replayConfig ?? session.config)) { models(); return; }
  if (!session.branches.length) {
    session.branch = uid();
    session.branches.push({ id: session.branch, messages: structuredClone(session.messages) });
  } else {
    const active = session.branches.find(item => item.id === session.branch);
    if (active) active.messages = structuredClone(session.messages);
  }
  const original = session.messages[index];
  const messages = session.messages.slice(0, index);
  if (original.role === 'user') messages.push({ ...original, text });
  session.branch = uid();
  session.messages = messages;
  session.branches.push({ id: session.branch, messages: structuredClone(messages) });
  pageLimit = 36;
  renderedSession = null;
  void generation.launch(session, flags);
}

function chatMenu(session) {
  if (!session) return;
  const view = openDialog(session.title);
  const list = el('div', undefined, 'menu-list');
  list.append(
    button('重命名', 'rename', session.id, 'small-button', 'pen'),
    button(session.pinned ? '取消置顶' : '置顶对话', 'pin', session.id, 'small-button', 'pin'),
    button(session.archived ? '取消归档' : '归档对话', 'archive-session', session.id, 'small-button', 'archive'),
    button('对话参数', 'conversation-settings', session.id, 'small-button', 'settings'),
    button('删除对话', 'delete', session.id, 'small-button danger', 'trash'),
  );
  view.append(list);
}

function editText(title, label, initial, save, long = false) {
  const view = openDialog(title);
  const form = el('form', undefined, 'fields');
  form.append(control(label, 'value', initial, long ? 'textarea' : 'text', { maxLength: long ? 100000 : 140, required: true }));
  actions(form, long ? '保存并生成分支' : '保存');
  form.addEventListener('submit', event => {
    event.preventDefault();
    const value = form.elements.value.value.trim();
    if (!value) return;
    dialog.close();
    save(value);
  });
  view.append(form);
  form.elements.value.focus();
  if (!long) form.elements.value.select();
}

function conversationSettings(session) {
  if (!session) return;
  const view = openDialog('对话参数');
  const form = el('form', undefined, 'fields');
  const config = session.config;
  form.append(control('系统提示词', 'system', config.system, 'textarea', { maxLength: 20000 }));
  form.append(row(
    control('Temperature', 'temperature', config.temperature, 'number', { min: 0, max: 2, step: 0.01, required: true }),
    control('Top P', 'topP', config.topP, 'number', { min: 0, max: 1, step: 0.01, required: true }),
    control('输出上限（token）', 'max', config.max, 'number', { min: 1, max: 1000000, placeholder: '使用模型默认值' }),
  ));
  actions(form);
  form.addEventListener('submit', event => {
    event.preventDefault();
    Object.assign(config, { system: form.elements.system.value, temperature: Number(form.elements.temperature.value),
      topP: Number(form.elements.topP.value), max: Number(form.elements.max.value) || null });
    dialog.close();
    commit();
    notify('对话参数已保存');
  });
  view.append(form);
}

async function stageFiles(files) {
  const session = current();
  for (const file of files) {
    if (session.draft.files.length >= 10) { notify('每条消息最多添加 10 个附件。'); break; }
    try {
      const descriptor = await addAttachment(file);
      if (state.sessions.includes(session)) session.draft.files.push(descriptor);
    } catch (error) { notify(error.message); }
  }
  commit();
  paint(session);
}

async function previewFile(file) {
  const blob = await readAttachment(file.id);
  if (!blob) { notify('原始附件不可用。'); return; }
  const view = openDialog(file.name, true);
  const save = button('下载原文件', '', undefined, 'small-button', 'download');
  save.addEventListener('click', () => download(blob, file.name));
  view.append(save);
  if (file.mime === 'application/pdf') {
    try {
      const { previewPdf } = await import('./pdf-preview.mjs');
      await previewPdf(blob, view, dialog);
    } catch (error) { view.append(el('p', '无法预览 PDF：' + error.message)); }
  } else if (/^image\/(?:png|jpeg|webp|gif)$/.test(file.mime)) {
    const url = URL.createObjectURL(blob);
    const image = el('img', undefined, 'attachment-image');
    image.alt = file.name;
    image.src = url;
    view.append(image);
    dialog.addEventListener('close', () => URL.revokeObjectURL(url), { once: true });
  } else if (/\.(?:html|svg)$/i.test(file.name)) view.append(staticPreview(await blob.text()));
  else if (file.mime.startsWith('text/') || /\.(?:txt|md|json|csv|log|xml)$/i.test(file.name)) {
    view.append(el('pre', await blob.slice(0, 200000).text(), 'attachment-text'));
  }
}

function messageDetails(message) {
  const view = openDialog('回复详情');
  if (message.status) view.append(el('p', message.status));
  if (message.model) view.append(el('p', '模型 · ' + message.model));
  if (message.tools?.length) {
    view.append(el('h3', '工具记录'));
    for (const tool of message.tools) {
      view.append(el('p', tool.name + ' · ' + tool.status));
      if (tool.detail) view.append(el('pre', tool.detail, 'attachment-text'));
    }
  }
  if (message.usage) {
    view.append(el('h3', '用量'));
    const usage = message.usage;
    const input = usage.input_tokens ?? usage.prompt_tokens ?? usage.promptTokenCount;
    const output = usage.output_tokens ?? usage.completion_tokens ??
      (usage.candidatesTokenCount === undefined ? undefined : usage.candidatesTokenCount + (usage.thoughtsTokenCount ?? 0));
    if (typeof input === 'number' || typeof output === 'number') {
      view.append(el('p', '输入 ' + (input ?? '—') + ' token · 输出 ' + (output ?? '—') + ' token'));
    }
    const price = message.pricing;
    if (price?.input && price?.output && typeof input === 'number' && typeof output === 'number') {
      const estimate = (input * Number(price.input) + output * Number(price.output)) / 1000000;
      view.append(el('p', '参考费用 ' + estimate.toLocaleString('zh-CN', { maximumFractionDigits: 8 }) + ' ' + price.currency));
    }
    view.append(details('供应商原始用量', el('pre', JSON.stringify(usage, null, 2), 'attachment-text')));
  }
}

async function copy(value) {
  try { await navigator.clipboard.writeText(value); notify('已复制'); }
  catch { notify('浏览器未允许访问剪贴板。'); }
}

const operations = {
  new() { archive = false; switchSession(newSession(), true); },
  sidebar() {
    const open = $('#sidebar').classList.toggle('open');
    $('.scrim').hidden = !open;
    $('.mobile-menu').setAttribute('aria-expanded', open);
  },
  settings() { settings(); },
  models,
  close() { dialog.close(); },
  theme() {
    state.options.theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    updateTheme();
    commit();
  },
  archive() { archive = !archive; renderHistory(); },
  session(session, value) {
    const target = state.sessions.find(item => item.id === value);
    if (target) switchSession(target);
  },
  'chat-menu'(session, value) { chatMenu(value ? state.sessions.find(item => item.id === value) : session); },
  'conversation-settings'(session, id) { conversationSettings(state.sessions.find(item => item.id === id)); },
  rename(session, id) {
    const target = state.sessions.find(item => item.id === id);
    if (target) editText('重命名对话', '对话名称', target.title, value => {
      target.title = value; target.named = true; commit(); paint(target);
    });
  },
  pin(session, id) {
    const target = state.sessions.find(item => item.id === id);
    target.pinned = !target.pinned;
    dialog.close();
    commit(); paint(target);
  },
  'archive-session'(session, id) {
    const target = state.sessions.find(item => item.id === id);
    target.archived = !target.archived;
    dialog.close();
    commit(); paint(target);
    notify(target.archived ? '对话已归档' : '对话已恢复到历史记录');
  },
  async delete(session, id) {
    const target = state.sessions.find(item => item.id === id);
    if (!target || !window.confirm('永久删除「' + target.title + '」及其分支？')) return;
    const stopped = generation.stop(target);
    target.job = null;
    await stopped;
    state.sessions = state.sessions.filter(item => item.id !== id);
    dialog.close();
    if (target === session) switchSession(state.sessions.find(item => !item.archived) ?? newSession());
    else { commit(); renderHistory(); }
    await pruneAttachments(state.sessions).catch(() => {});
  },
  attach() { $('#attachments').click(); },
  file(session, value, target) { return previewFile(target.file); },
  'remove-file'(session, id) {
    session.draft.files = session.draft.files.filter(file => file.id !== id);
    commit(); paint(session);
  },
  'search-toggle'(session) { session.config.search = !session.config.search; commit(); paint(session); },
  stop(session) { return generation.stop(session); },
  'remove-queued'(session, id) { session.queue = session.queue.filter(item => item.id !== id); commit(); paint(session); },
  'send-queued'(session) { generation.dequeue(session); },
  older(session) {
    const oldHeight = scroll.scrollHeight;
    pageLimit += 36;
    paint(session, false, true);
    scroll.scrollTop += scroll.scrollHeight - oldHeight;
  },
  branch(session, value) {
    if (session.job || session.queue.length) return;
    const target = session.branches[Number(value)];
    if (!target) return;
    const active = session.branches.find(item => item.id === session.branch);
    if (active) active.messages = structuredClone(session.messages);
    session.messages = structuredClone(target.messages);
    session.branch = target.id;
    pageLimit = 36;
    commit(); paint(session, false, true);
    scroll.scrollTop = scroll.scrollHeight;
  },
  copy(session, id) { return copy(session.messages.find(message => message.id === id)?.text ?? ''); },
  'copy-code'(session, value, target) { return copy(target.closest('.code-panel').raw); },
  'preview-code'(session, value, target) { openDialog('静态代码预览', true).append(staticPreview(target.closest('.code-panel').raw)); },
  'message-details'(session, id) {
    const message = session.messages.find(item => item.id === id);
    if (message) messageDetails(message);
  },
  edit(session, id) {
    const index = session.messages.findIndex(message => message.id === id);
    if (index < 0) return;
    editText('编辑消息', '消息内容', session.messages[index].text, value => branch(session, index, value), true);
  },
  retry(session, id) { retry(session, id); },
  'retry-tools'(session, id) { retry(session, id, { skipTools: true }); },
  'retry-search'(session, id) { retry(session, id, { skipSearch: true }); },
};

function retry(session, id, flags) {
  const index = session.messages.findIndex(message => message.id === id);
  if (index > 0 && session.messages.slice(0, index).some(message => message.role === 'user')) {
    const replayConfig = flags && session.messages[index].replayConfig;
    branch(session, index, '', replayConfig ? { ...flags, replayConfig } : flags);
  }
}

let reasoningLifetime;
let effortChanged = false;
reasoningPopover.addEventListener('beforetoggle', event => {
  const opening = event.newState === 'open';
  effortButton.setAttribute('aria-expanded', opening);
  if (!opening) {
    reasoningLifetime?.abort();
    if (effortChanged) commit();
    effortChanged = false;
    return;
  }

  const session = current();
  const selected = lookup(session.config);
  const caps = selected && capabilities(selected.model, selected.provider.protocol);
  const initial = caps?.levels.includes(session.config.effort) ? session.config.effort : 'default';
  reasoningLifetime = new AbortController();
  renderReasoningPopover({
    panel: reasoningPopover,
    anchor: effortButton,
    model: selected && (selected.model.name || selected.model.id),
    caps, initial,
    options: state.options,
    apply(value) {
      if (session.config.effort === value) return;
      session.config.effort = value;
      effortChanged = true;
      commit(true);
      renderEffort(value);
    },
    chooseModel: models,
    signal: reasoningLifetime.signal,
  });
});

document.addEventListener('click', async event => {
  const target = event.target.closest('[data-action]');
  if (!target || !Object.hasOwn(operations, target.dataset.action)) return;
  try { await operations[target.dataset.action](current(), target.dataset.value, target); }
  catch (error) { notify(error.message); }
});
dialog.addEventListener('click', event => {
  if (event.target !== dialog) return;
  const bounds = dialog.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
});
$('#history-search').addEventListener('input', renderHistory);
$('#composer').addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
prompt.addEventListener('input', () => {
  const session = current();
  const hadDraft = !!session.draft.text;
  session.draft.text = prompt.value;
  if (hadDraft !== !!prompt.value) renderHistory();
  $('.send').disabled = !(prompt.value.trim() || session.draft.files.length);
  resizeComposer();
  commit(true);
});
prompt.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault(); sendMessage();
  }
});
prompt.addEventListener('paste', event => {
  const files = [...event.clipboardData.files];
  if (files.length) { event.preventDefault(); void stageFiles(files); }
});
const composer = $('#composer');
composer.addEventListener('dragover', event => {
  if (event.dataTransfer.types.includes('Files')) { event.preventDefault(); composer.classList.add('drag'); }
});
composer.addEventListener('dragleave', event => { if (!composer.contains(event.relatedTarget)) composer.classList.remove('drag'); });
composer.addEventListener('drop', event => {
  event.preventDefault(); composer.classList.remove('drag'); void stageFiles([...event.dataTransfer.files]);
});
$('#attachments').addEventListener('change', event => {
  const files = [...event.target.files]; event.target.value = ''; void stageFiles(files);
});
$('#backup').addEventListener('change', async event => {
  const file = event.target.files[0]; event.target.value = '';
  if (!file) return;
  try {
    const imported = await restoreBackup(file);
    state.sessions.push(...imported);
    dialog.close();
    if (imported.length) {
      archive = imported[0].archived;
      switchSession(imported[0]);
    }
    notify('已导入 ' + imported.length + ' 个对话');
  } catch (error) { notify('导入失败：' + error.message); }
});
document.addEventListener('keydown', event => {
  if (event.isComposing || dialog.open) return;
  if (event.altKey && event.key.toLowerCase() === 'n') { event.preventDefault(); operations.new(); }
  else if (event.key === '/' && !event.target.closest('input, textarea, select, [contenteditable]')) {
    event.preventDefault();
    if (matchMedia('(max-width: 740px)').matches && !$('#sidebar').classList.contains('open')) operations.sidebar();
    $('#history-search').focus();
  } else if (event.key === 'Escape' && $('#sidebar').classList.contains('open')) operations.sidebar();
});
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', updateTheme);
document.addEventListener('visibilitychange', () => { if (document.hidden && current()) commit(); });

mountIcons();
if (hosted) $('#storage-note').textContent = '历史保存在此浏览器，生成任务由云端暂存';
try {
  const saved = await loadWorkspace();
  if (saved) Object.assign(state, saved, { options: { ...defaults, ...saved.options } });
  if (state.options.reasoningColorMode === 'cyan') state.options.reasoningColorMode = 'theme';
  const keys = JSON.parse(localStorage.getItem('aera-remembered-keys') ?? '{}');
  const temporary = JSON.parse(sessionStorage.getItem('aera-tab-keys') ?? '{}');
  state.keys = { ...keys, ...temporary };
  state.remembered = Object.keys(keys);
} catch { notify('浏览器无法读取本地工作区。'); }
updateTheme();
switchSession(current() ?? newSession());
$('.shell').inert = false;
$('.shell').removeAttribute('aria-busy');
generation.resume();
