import { efforts, protocols, capabilities } from './capabilities.mjs';
import { uid } from './files.mjs';
import { publicHttps } from './hosted-url.mjs';
import { hosted } from './runtime.mjs';
import { button, el, mountIcons } from './ui.mjs';

const presets = {
  chat_completions: ['https://api.openai.com/v1', 'OpenAI 兼容'],
  responses: ['https://api.openai.com/v1', 'OpenAI'],
  anthropic: ['https://api.anthropic.com', 'Anthropic'],
  gemini: ['https://generativelanguage.googleapis.com/v1beta', 'Google Gemini'],
};

export function openDialog(title, wide = false) {
  const dialog = document.querySelector('#dialog');
  dialog.classList.toggle('wide', wide);
  dialog.innerHTML = '<div class="dialog-head"><h2 id="dialog-title"></h2><button class="plain-icon" data-action="close" aria-label="关闭" title="关闭"><span data-icon="close"></span></button></div><div class="dialog-body"></div>';
  dialog.querySelector('h2').textContent = title;
  mountIcons(dialog);
  if (!dialog.open) dialog.showModal();
  dialog.scrollTop = 0;
  return dialog.querySelector('.dialog-body');
}

export function control(label, name, value, kind = 'text', extra = {}) {
  const group = el('label', undefined, kind === 'checkbox' ? 'checkline' : 'field');
  if (kind !== 'checkbox') group.append(el('span', label));
  const input = el(kind === 'select' || kind === 'textarea' ? kind : 'input');
  input.name = name;
  if (input.tagName === 'INPUT') input.type = kind;
  if (kind === 'checkbox') input.checked = !!value;
  else if (kind === 'select') {
    for (const [id, text] of extra.options) input.add(new Option(text, id));
    input.value = value ?? '';
  } else input.value = value ?? '';
  for (const [key, setting] of Object.entries(extra)) {
    if (!['help', 'options'].includes(key)) input[key] = setting;
  }
  group.append(input);
  if (kind === 'checkbox') group.append(document.createTextNode(label));
  if (extra.help) group.append(el('small', extra.help));
  return group;
}

export function row(...items) {
  const group = el('div', undefined, 'form-row' + (items.length === 3 ? ' thirds' : ''));
  group.append(...items);
  return group;
}

export function actions(form, caption = '保存', other = []) {
  const area = el('div', undefined, 'dialog-actions');
  const submit = el('button', caption, 'primary');
  submit.type = 'submit';
  area.append(...other, button('取消', 'close'), submit);
  form.append(area);
}

export function showError(place, text) {
  let error = place.querySelector('.dialog-error');
  if (!error) {
    error = el('div', undefined, 'dialog-error');
    error.setAttribute('role', 'alert');
    place.append(error);
  }
  error.hidden = !text;
  error.textContent = text ?? '';
}

export function modelOptions(providers, emptyLabel) {
  return [['', emptyLabel], ...providers.flatMap(provider => provider.models.map(model =>
    [JSON.stringify([provider.id, model.id]), provider.name + ' · ' + (model.name || model.id)]))];
}

export function createSettings(ctx) {
  const { state, api, commit, notify, selectModel, exportData, importData } = ctx;
  function settings(tab = 'providers') {
    const view = openDialog('Aera 设置', true);
    const tabs = el('nav', undefined, 'dialog-tabs');
    tabs.setAttribute('aria-label', '设置分类');
    for (const [id, text] of [['providers', '模型供应商'], ['tools', '搜索与工具'], ['recognition', '附件与标题'], ['appearance', '外观'], ['data', '数据与备份']]) {
      const item = button(text, '');
      item.setAttribute('aria-pressed', id === tab);
      item.addEventListener('click', () => settings(id));
      tabs.append(item);
    }
    view.append(tabs);
    if (tab === 'providers') providers(view);
    else if (tab === 'tools') tools(view);
    else if (tab === 'recognition') recognition(view);
    else if (tab === 'appearance') appearance(view);
    else {
      view.append(el('p', '对话和附件由当前浏览器保存。备份 ZIP 可用于在 Aera 网页版和 Android 版之间迁移对话。'));
      const panel = el('div', undefined, 'menu-list');
      const exp = button('导出对话 ZIP', '', undefined, 'small-button', 'download');
      exp.addEventListener('click', async () => {
        exp.disabled = true;
        try { await exportData(); } finally { exp.disabled = false; }
      });
      const imp = button('导入对话 ZIP', '', undefined, 'small-button', 'archive');
      imp.addEventListener('click', importData);
      panel.append(exp, imp);
      view.append(panel);
    }
  }

  function providers(view) {
    view.append(el('p', '配置自己的接口服务。模型 ID 可以手动添加，也可以从供应商获取。'));
    const list = el('div', undefined, 'model-list');
    for (const provider of state.providers) {
      const line = el('div', undefined, 'model-line');
      line.append(el('span', provider.name + ' · ' + protocols[provider.protocol] + ' · ' + provider.models.length + ' 个模型'));
      const edit = button('编辑', '', undefined, 'small-button', 'pen');
      edit.addEventListener('click', () => providerEditor(provider));
      line.append(edit);
      list.append(line);
    }
    view.append(list);
    const add = button('新增供应商', '', undefined, 'primary', 'plus');
    add.addEventListener('click', () => providerEditor());
    view.append(add);
  }

  function providerEditor(existing) {
    const draft = existing ? structuredClone(existing) : { id: uid(), protocol: 'chat_completions', name: '', api: '', models: [] };
    const view = openDialog(existing ? '编辑供应商' : '新增供应商', true);
    const form = el('form', undefined, 'fields');
    form.append(row(
      control('供应商名称', 'name', draft.name, 'text', { placeholder: '例如 OpenAI 或本地模型', required: true, maxLength: 100 }),
      control('接口协议', 'protocol', draft.protocol, 'select', { options: Object.entries(protocols) }),
    ));
    form.append(control('API 地址', 'api', draft.api, 'url', {
      required: true, placeholder: presets[draft.protocol][0],
      ...(hosted && { help: '云端运行需要公网 HTTPS 接口地址。' }),
    }));
    form.append(control('API Key', 'key', state.keys[draft.id] ?? '', 'password', { autocomplete: 'off', placeholder: '本地免密接口可留空' }));
    form.append(control('在此浏览器记住 API Key', 'remember', state.remembered.includes(draft.id), 'checkbox'));
    form.elements.protocol.addEventListener('change', () => {
      const [url, name] = presets[form.elements.protocol.value];
      if (!form.elements.api.value || Object.values(presets).some(([base]) => base === form.elements.api.value)) form.elements.api.value = url;
      if (!form.elements.name.value || Object.values(presets).some(([, preset]) => preset === form.elements.name.value)) form.elements.name.value = name;
    });
    const panel = el('div', undefined, 'settings-panel');
    panel.append(el('h3', '已配置模型'));
    const list = el('div', undefined, 'model-list');
    const refresh = () => {
      const expanded = new Set([...list.querySelectorAll('details[open]')].map(item => item.dataset.model));
      list.replaceChildren();
      if (!draft.models.length) list.append(el('p', '还没有模型。请填写模型 ID，或从接口获取。'));
      draft.models.forEach((model, index) => {
        const item = el('details');
        item.className = 'settings-panel';
        item.dataset.model = model.id;
        item.open = expanded.has(model.id);
        item.append(el('summary', model.name === model.id ? model.id : model.name + ' · ' + model.id));
        const data = el('div', undefined, 'fields');
        data.append(row(
          control('显示名称', 'display-' + index, model.name, 'text', { maxLength: 120, required: true }),
          control('上下文容量', 'context-' + index, model.context, 'number', { min: 512, max: 10000000, required: true }),
          control('最大输出', 'max-' + index, model.max, 'number', { min: 1, max: 1000000, required: true }),
        ));
        const providers = [...state.providers.filter(provider => provider.id !== draft.id), {
          ...draft, name: form.elements.name.value || '当前供应商', models: draft.models.filter(candidate => candidate !== model),
        }];
        const choices = [['inherit', '使用全局设置'], ...modelOptions(providers, '关闭')];
        data.append(row(
          control('PDF 转文字', 'pdf-ocr-' + index, model.pdfOcr ?? 'inherit', 'select', { options: choices }),
          control('图片转文字', 'image-ocr-' + index, model.imageOcr ?? 'inherit', 'select', { options: choices }),
        ));
        data.append(row(
          control('每百万输入 token 单价', 'input-price-' + index, model.inputPrice, 'number', { min: 0, step: 'any', placeholder: '可选' }),
          control('每百万输出 token 单价', 'output-price-' + index, model.outputPrice, 'number', { min: 0, step: 'any', placeholder: '可选' }),
          control('币种', 'currency-' + index, model.currency || 'USD', 'text', { maxLength: 6, placeholder: '例如 CNY 或 USD' }),
        ));
        data.append(control('自定义模型能力', 'custom-' + index, model.capabilityMode === 'custom', 'checkbox'));
        const advanced = row(
          control('思考档位（用逗号分隔）', 'levels-' + index,
            model.reasoningLevels ?? capabilities(model, form.elements.protocol.value).levels.slice(1).join(','), 'text',
            { help: Object.keys(efforts).slice(1).join(', ') }),
          control('支持 Temperature / Top P', 'sampling-' + index,
            model.supportsSampling ?? capabilities(model, form.elements.protocol.value).sampling, 'checkbox'),
        );
        advanced.hidden = model.capabilityMode !== 'custom';
        const remove = button('移除此模型', '', undefined, 'small-button danger', 'trash');
        remove.addEventListener('click', () => { draft.models.splice(index, 1); refresh(); });
        data.addEventListener('input', () => {
          model.name = form.elements['display-' + index].value;
          model.context = Number(form.elements['context-' + index].value);
          model.max = Number(form.elements['max-' + index].value);
          model.pdfOcr = form.elements['pdf-ocr-' + index].value;
          model.imageOcr = form.elements['image-ocr-' + index].value;
          model.inputPrice = form.elements['input-price-' + index].value;
          model.outputPrice = form.elements['output-price-' + index].value;
          model.currency = form.elements['currency-' + index].value.trim().toUpperCase();
          model.capabilityMode = form.elements['custom-' + index].checked ? 'custom' : '';
          model.reasoningLevels = form.elements['levels-' + index].value;
          model.supportsSampling = form.elements['sampling-' + index].checked;
          advanced.hidden = model.capabilityMode !== 'custom';
        });
        item.append(data, remove);
        list.append(item);
      });
    };
    const plusModel = id => {
      if (id && !draft.models.some(model => model.id === id)) {
        draft.models.push({ id, name: id, context: 128000, max: 4096 });
        refresh();
      }
    };
    const manual = control('模型 ID', 'manual', '', 'text', { placeholder: '例如 gpt-4o-mini', maxLength: 220 });
    const add = button('添加模型', '', undefined, 'small-button', 'plus');
    add.addEventListener('click', () => {
      plusModel(form.elements.manual.value.trim());
      form.elements.manual.value = '';
    });
    manual.querySelector('input').addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); add.click(); }
    });
    const fetchModels = button('获取模型列表', '', undefined, 'small-button', 'cycle');
    const found = el('div', undefined, 'model-list');
    fetchModels.addEventListener('click', async () => {
      const data = new FormData(form);
      fetchModels.disabled = true;
      showError(panel, '');
      try {
        const result = await api('/api/models', 'POST', { api: String(data.get('api')).trim(),
          protocol: data.get('protocol'), key: String(data.get('key')).trim() });
        found.replaceChildren();
        const ids = result.models.filter(id => !draft.models.some(model => model.id === id));
        if (!ids.length) found.append(el('p', '接口未返回新的模型。可以手动填写模型 ID。'));
        for (const id of ids) {
          const line = el('div', undefined, 'model-line');
          line.append(el('span', id));
          const choose = button('添加', '', undefined, 'small-button', 'plus');
          choose.addEventListener('click', () => { plusModel(id); line.remove(); });
          line.append(choose);
          found.append(line);
        }
      } catch (error) { showError(panel, error.message); }
      finally { fetchModels.disabled = false; }
    });
    const noSubmit = el('div', undefined, 'dialog-actions');
    noSubmit.append(add, fetchModels);
    panel.append(list, manual, noSubmit, found);
    form.append(panel);
    const other = [];
    if (existing) {
      const remove = button('删除供应商', '', undefined, 'small-button danger', 'trash');
      remove.addEventListener('click', () => {
        if (!window.confirm('删除此供应商及其 API Key？已有对话将保留。')) return;
        state.providers = state.providers.filter(provider => provider.id !== draft.id);
        ctx.setKey(draft.id, '', false);
        commit();
        settings();
      });
      other.push(remove);
    }
    actions(form, '保存供应商', other);
    form.addEventListener('submit', event => {
      event.preventDefault();
      const data = new FormData(form);
      draft.name = String(data.get('name')).trim();
      draft.protocol = data.get('protocol');
      draft.api = String(data.get('api')).trim();
      try {
        const url = hosted ? publicHttps(draft.api) : new URL(draft.api);
        if (!draft.name || !['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.hash || url.search) {
          throw new Error('请填写供应商名称及有效的 HTTP 或 HTTPS 地址。');
        }
        plusModel(String(data.get('manual')).trim());
        if (draft.models.some(model => !model.name.trim() || model.context < 512 || model.max < 1 || Number(model.inputPrice) < 0 || Number(model.outputPrice) < 0)) throw new Error('请检查所有模型的名称和 token 设置。');
        const index = state.providers.findIndex(item => item.id === draft.id);
        if (index < 0) state.providers.push(draft);
        else state.providers[index] = draft;
        ctx.setKey(draft.id, String(data.get('key')).trim(), data.has('remember'));
        commit();
        notify('供应商设置已保存');
        if (draft.models.length && !ctx.currentModel()) {
          selectModel(draft, draft.models[0]);
          document.querySelector('#dialog').close();
        } else settings();
      } catch (error) { showError(form, error.message); }
    });
    view.append(form);
    refresh();
  }

  function tools(view) {
    const flags = state.options;
    const form = el('form', undefined, 'fields');
    form.append(control('联网搜索方式', 'searchMode', flags.searchMode, 'select', {
      options: [['automatic', '自动选择'], ['builtin', '供应商内置搜索'], ['tavily', 'Tavily']],
      help: '自动模式优先使用已知支持搜索的模型，其余使用 Tavily。每个对话可单独开启搜索。',
    }));
    form.append(control('Tavily API Key', 'tavilyKey', state.keys.tavily ?? '', 'password', { autocomplete: 'off' }));
    form.append(control('在此浏览器记住 Tavily API Key', 'remember', state.remembered.includes('tavily'), 'checkbox'));
    form.append(row(
      control('搜索结果数量', 'tavilyCount', flags.tavilyCount, 'number', { min: 1, max: 20, required: true }),
      control('搜索深度', 'tavilyDepth', flags.tavilyDepth, 'select', { options: [['basic', '基础'], ['advanced', '深入']] }),
    ));
    form.append(control('允许 Anthropic / Gemini 内置工具读取网页', 'urlContext', flags.urlContext, 'checkbox'));
    form.append(control('允许可用的供应商内置工具执行代码', 'code', flags.code, 'checkbox'));
    actions(form);
    form.addEventListener('submit', event => {
      event.preventDefault();
      const data = new FormData(form);
      Object.assign(flags, {
        searchMode: data.get('searchMode'), tavilyCount: Number(data.get('tavilyCount')),
        tavilyDepth: data.get('tavilyDepth'), urlContext: data.has('urlContext'), code: data.has('code'),
      });
      ctx.setKey('tavily', String(data.get('tavilyKey')).trim(), data.has('remember'));
      commit();
      document.querySelector('#dialog').close();
      notify('工具设置已保存');
    });
    view.append(form);
  }

  function recognition(view) {
    const flags = state.options;
    const form = el('form', undefined, 'fields');
    const ocrModels = modelOptions(state.providers, '关闭');
    form.append(el('p', '选择视觉模型后，Aera 会把文件图像发送给该模型识别，再把文字交给当前聊天模型。'));
    form.append(row(
      control('PDF 转文字', 'pdfOcr', flags.pdfOcr, 'select', { options: ocrModels }),
      control('图片转文字', 'imageOcr', flags.imageOcr, 'select', { options: ocrModels }),
    ));
    form.append(control('自动生成对话标题', 'autoTitle', flags.autoTitle, 'checkbox'));
    form.append(control('命名模型', 'titleModel', flags.titleModel, 'select', { options: modelOptions(state.providers, '使用当前对话模型') }));
    form.append(control('命名提示词', 'titlePrompt', flags.titlePrompt, 'textarea', { required: true, maxLength: 6000 }));
    actions(form);
    form.addEventListener('submit', event => {
      event.preventDefault();
      const data = new FormData(form);
      for (const name of ['pdfOcr', 'imageOcr', 'titleModel', 'titlePrompt']) flags[name] = data.get(name);
      flags.autoTitle = data.has('autoTitle');
      commit();
      document.querySelector('#dialog').close();
      notify('附件与标题设置已保存');
    });
    view.append(form);
  }

  function appearance(view) {
    const flags = state.options;
    const form = el('form', undefined, 'fields');
    form.append(row(
      control('显示模式', 'theme', flags.theme, 'select', { options: [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']] }),
      control('强调色', 'accent', flags.accent || '#5c53bf', 'color'),
    ));
    form.append(control('使用自定义强调色', 'customAccent', !!flags.accent, 'checkbox'));
    const reasoningColor = control('思考强度动画配色', 'reasoningColorMode', flags.reasoningColorMode, 'select', {
      options: [['theme', '跟随主题'], ['blue', 'GPT 蓝色'], ['purple', 'GPT 紫色'], ['custom', '自定义']],
    });
    const customColor = control('自定义动画颜色', 'reasoningCustomColor', flags.reasoningCustomColor, 'color');
    form.append(reasoningColor, customColor);
    const showColor = () => { customColor.hidden = form.elements.reasoningColorMode.value !== 'custom'; };
    form.elements.reasoningColorMode.addEventListener('change', showColor);
    showColor();
    form.append(control('欢迎标题', 'welcomeTitle', flags.welcomeTitle, 'text', { placeholder: '今天想聊些什么？', maxLength: 90 }));
    form.append(control('欢迎说明', 'welcomeCopy', flags.welcomeCopy, 'text', { placeholder: '留空时仅显示欢迎标题', maxLength: 180 }));
    actions(form);
    form.addEventListener('submit', event => {
      event.preventDefault();
      const data = new FormData(form);
      for (const name of ['theme', 'welcomeTitle', 'welcomeCopy', 'reasoningColorMode', 'reasoningCustomColor']) flags[name] = String(data.get(name)).trim();
      flags.accent = data.has('customAccent') ? data.get('accent') : '';
      commit();
      document.querySelector('#dialog').close();
      notify('外观设置已保存');
    });
    view.append(form);
  }

  function models() {
    const view = openDialog('选择模型');
    const searchable = control('搜索模型', 'filter', '', 'search', { placeholder: '名称、模型 ID 或供应商' });
    const list = el('div', undefined, 'model-list');
    const search = searchable.querySelector('input');
    function render() {
      list.replaceChildren();
      const term = search.value.trim().toLowerCase();
      for (const provider of state.providers) for (const model of provider.models) {
        if (term && !(provider.name + ' ' + model.id + ' ' + model.name).toLowerCase().includes(term)) continue;
        const line = el('div', undefined, 'model-line');
        const selected = ctx.currentModel()?.model === model;
        const pick = button(model.name || model.id, '', undefined, 'small-button' + (selected ? ' selected' : ''), selected ? 'check' : 'layers');
        pick.title = model.id;
        pick.addEventListener('click', () => {
          selectModel(provider, model);
          document.querySelector('#dialog').close();
        });
        line.append(pick, el('span', provider.name, 'history-group'));
        list.append(line);
      }
      if (!list.childElementCount) list.append(el('p', term ? '找不到匹配的模型。' : '请先添加一个模型供应商。'));
    }
    search.addEventListener('input', render);
    const manage = button('管理供应商', '', undefined, 'small-button', 'settings');
    manage.addEventListener('click', () => settings());
    view.append(searchable, list, manage);
    render();
    search.focus();
  }

  return { settings, models };
}
