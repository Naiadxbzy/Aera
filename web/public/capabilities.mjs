export const protocols = {
  chat_completions: 'Chat Completions',
  responses: 'OpenAI Responses',
  anthropic: 'Anthropic Messages',
  gemini: 'Google Gemini',
};
export const efforts = {
  default: '自动', none: '关闭', enabled: '开启', minimal: '极低', low: '低',
  medium: '中', high: '高', xhigh: '很高', max: '最高', ultra: '极致',
};

export function canonical(id) {
  const value = id.trim().toLowerCase();
  return (value.match(/(?:^|[/\s:])((?:gpt-|claude-|deepseek-|gemini-|grok-|gork-|kimi-|glm-|moonshot-|o[134](?:-|$)).*)/)?.[1] ?? value)
    .replace(/:(?:free|online|nitro)$/, '');
}

function version(id, prefix) {
  const [, major = '0', minor = ''] = id.slice(prefix.length).match(/^(\d+)(?:[.-](\d+))?/) ?? [];
  return Number(major) + (minor.length <= 2 ? Number('0.' + (minor || '0')) : 0);
}

export function capabilities(model, protocol) {
  const id = canonical(model?.id ?? '');
  let family = '', control = 'fixed', levels = '', sampling = true;
  if (id.startsWith('gpt-') || /^o[134](?:-|$)/.test(id)) {
    family = 'gpt';
    if (id.startsWith('gpt-6')) {
      levels = (/^gpt-6-(sol|luna)/.test(id) ? 'none,' : '') + 'low,medium,high,xhigh,max';
    } else if (id.startsWith('gpt-5') && !/chat|search/.test(id)) {
      const v = version(id, 'gpt-');
      if (id.includes('pro')) levels = id.startsWith('gpt-5-pro') ? 'high' : 'medium,high,xhigh';
      else if (id.includes('codex')) levels = id.startsWith('gpt-5-codex') ? 'low,medium,high' : 'low,medium,high,xhigh';
      else levels = v >= 5.6 ? 'none,low,medium,high,xhigh,max' : v >= 5.2 ? 'none,low,medium,high,xhigh' :
        v >= 5.1 ? 'none,low,medium,high' : 'minimal,low,medium,high';
    } else if (/^o[134](?:-|$)/.test(id) && !/^o1-(mini|preview)/.test(id)) {
      levels = 'low,medium,high';
    }
    sampling = !levels && !/^gpt-[56]/.test(id);
  } else if (id.startsWith('claude-')) {
    family = 'claude';
    const match = id.match(/^claude-([1-9])(?:[.-]([0-9])(?=-|$))?(?=-|$)/) ||
      id.match(/(?:opus|sonnet|haiku|fable|mythos)-([1-9])(?:[.-]([0-9])(?=-|$))?(?=-|$)/);
    const v = id.includes('mythos-preview') ? 4.6 : match ? Number(match[1] + '.' + (match[2] ?? '0')) : 0;
    if (v >= 4.6) {
      control = 'claude_adaptive';
      const forced = /fable|mythos/.test(id) || (id.includes('opus') && v >= 5.5);
      levels = (forced ? '' : 'none,') + 'low,medium,high' + (v >= 4.7 ? ',xhigh,max' : ',max');
    } else if (v >= 4.5 && id.includes('opus')) {
      control = 'claude_legacy';
      levels = 'none,low,medium,high';
    } else if (v >= 3.7) {
      control = 'claude_budget';
      levels = 'none,enabled';
    }
    sampling = !levels;
  } else if (id.startsWith('deepseek-')) {
    family = 'deepseek';
    sampling = false;
    if (/v4|^deepseek-(flash|pro)$/.test(id)) levels = 'none,low,high,max';
    else if (/v3[.-][12]/.test(id)) { control = 'toggle'; levels = 'none,enabled'; }
  } else if (id.startsWith('gemini-') && !/live|embedding/.test(id)) {
    family = 'gemini';
    const v = version(id, 'gemini-');
    if (v >= 3) {
      control = 'gemini_level';
      if (id.includes('3.1-flash-lite-image')) levels = 'minimal,high';
      else if (id.includes('flash') && v < 3.7) levels = 'minimal,low,medium,high';
      else if (id.includes('pro') && v < 3.1) levels = 'low,high';
      else levels = 'low,medium,high';
    } else if (v >= 2.5) {
      control = 'gemini_budget';
      levels = (id.includes('pro') ? '' : 'none,') + 'low,medium,high';
    }
  } else if (/^g(ro|or)k-/.test(id)) {
    family = 'grok';
    const v = version(id, id.slice(0, 5));
    if (v >= 4.6) levels = 'low,medium,high,xhigh';
    else if (v >= 4.5) levels = 'low,medium,high';
    else if (v >= 4.3) levels = 'none,low,medium,high';
    else if (id.includes('3-mini')) levels = 'low,high';
  } else if (/^(kimi|moonshot)-/.test(id)) {
    family = 'kimi';
    sampling = false;
    if (id.startsWith('kimi-k3')) levels = 'low,high,max';
    else if (/^kimi-k2[.-][56]/.test(id)) { control = 'toggle'; levels = 'none,enabled'; }
  } else if (id.startsWith('glm-')) {
    family = 'glm';
    const v = version(id, 'glm-');
    if (v >= 5.3) levels = 'low,high,max';
    else if (v >= 5.2) levels = 'none,high,max';
    else if (v >= 4.5) { control = 'toggle'; levels = 'none,enabled'; }
  }
  if (control === 'fixed' && levels) control = 'effort';
  if ((protocol === 'anthropic' && !['claude', 'deepseek'].includes(family)) ||
      (protocol === 'gemini' && family !== 'gemini') ||
      (protocol === 'responses' && ['toggle', 'claude_budget'].includes(control))) levels = '';
  if (model?.capabilityMode === 'custom') {
    levels = model.reasoningLevels ?? levels;
    sampling = model.supportsSampling ?? sampling;
  }
  return {
    family, control, sampling,
    levels: [...new Set(['default', ...levels.split(',').map(level => level.trim()).filter(level => Object.hasOwn(efforts, level))])],
    preserveReasoning: /^kimi-k(?:3|2[.-]7)/.test(id),
    tokenKey: /^(gpt-[56]|o[134](?:-|$))/.test(id) ? 'max_completion_tokens' : 'max_tokens',
  };
}

export function searchRouting(config, options, protocol, id, api) {
  const isDeepSeek = new URL(api).hostname === 'api.deepseek.com';
  const native = protocol === 'anthropic' || protocol === 'gemini';
  const responses = protocol === 'responses';
  const deepSeekSearch = !isDeepSeek || (canonical(id) === 'deepseek-v4-pro' && (responses || protocol === 'anthropic'));
  const supported = native || responses || /^(gpt-5-search-api|gpt-4o(?:-mini)?-search-preview)$/.test(canonical(id));
  const builtin = config.search && (options.searchMode === 'builtin' ||
    (options.searchMode !== 'tavily' && supported && deepSeekSearch));
  if (builtin && !deepSeekSearch) throw new Error('当前 DeepSeek 官方模型和协议不支持内置搜索，请使用 Tavily。');
  return {
    builtin, tavily: config.search && !builtin,
    urlContext: !isDeepSeek && native && options.urlContext === true,
    code: !isDeepSeek && (native || responses) && options.code === true,
  };
}
