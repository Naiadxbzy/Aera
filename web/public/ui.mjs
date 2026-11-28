import { marked } from '/vendor/marked.js';
import DOMPurify from '/vendor/purify.js';

const icons = {
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="10.6" cy="10.6" r="5.6"/><path d="m15 15 4 4"/>',
  archive: '<rect x="4" y="4" width="16" height="5" rx="1.5"/><path d="M5.5 9v9.5A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5V9M10 13h4"/>',
  chat: '<path d="M20.5 11.5a8.5 8.5 0 0 1-8.5 8.5H5l1.3-3.1A8.5 8.5 0 1 1 20.5 11.5Z"/><path d="M9 11.5h6"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="m19.4 13.5 1.1 1.2-1.4 2.4-1.6-.3-1.4.8-.5 1.6h-2.8l-.5-1.6-1.5-.8-1.6.3-1.4-2.4 1.1-1.2v-1.7l-1.1-1.2 1.4-2.4 1.6.3 1.5-.8.5-1.6h2.8l.5 1.6 1.4.8 1.6-.3 1.4 2.4-1.1 1.2z" transform="translate(-2 -1)"/>',
  shield: '<path d="M12 22s7-3.3 7-9V6l-7-3-7 3v7c0 5.7 7 9 7 9Z"/><path d="m9.4 12 1.6 1.6 3.6-3.6"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
  moon: '<path d="M20 15.3A8 8 0 0 1 8.7 4 8.1 8.1 0 1 0 20 15.3Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  sidebar: '<rect x="3.5" y="5" width="17" height="14" rx="2"/><path d="M9 5v14"/>',
  pen: '<path d="m5 16-1 4 4-1L19 8l-3-3L5 16Zm9-9 3 3M12 20h8"/>',
  file: '<path d="M6 3h8l4 4v14H6zM14 3v5h5M9 12h6m-6 4h6"/>',
  clip: '<path d="M8.2 13.1 14 7.3a2.3 2.3 0 0 1 3.3 3.3l-7.2 7.2a4 4 0 1 1-5.7-5.6L12 4.6"/>',
  globe: '<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4a13 13 0 0 0 0 16m0-16a13 13 0 0 1 0 16"/>',
  spark: '<path d="m12 2 2.1 6.9L21 11l-6.9 2.1L12 20l-2.1-6.9L3 11l6.9-2.1L12 2Z"/>',
  reasoning: '<path d="M4 12h8m5 0h3"/><circle cx="14.5" cy="12" r="2.5"/>',
  send: '<path d="M12 18V6m-5 5 5-5 5 5"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  cycle: '<path d="M20 7v5h-5M4 17v-5h5m10.3-.2A7.4 7.4 0 0 0 6.4 7.1m-1.7 5.1a7.4 7.4 0 0 0 12.9 4.7"/>',
  pin: '<path d="m9 13-5 7m6-17 9 6-3 1-2 4 1 3-1 1-8-5 1-1 3-1 2-4z"/>',
  check: '<path d="m5 12 4 4 10-10"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 3v.1"/>',
  download: '<path d="M12 3v12m-4-4 4 4 4-4M5 15v5h14v-5"/>',
  user: '<circle cx="12" cy="8" r="3"/><path d="M5 20a7 7 0 1 1 14 0"/>',
  trash: '<path d="M4 6h16m-13 0 1 14h8l1-14M9 6V3h6v3"/>',
};

export function icon(name) {
  return '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (icons[name] ?? '') + '</svg>';
}

export function mountIcons(scope = document) {
  for (const el of scope.querySelectorAll('[data-icon]')) el.innerHTML = icon(el.dataset.icon);
}

export function el(tag, value, css) {
  const element = document.createElement(tag);
  if (value !== undefined) element.textContent = value;
  if (css) element.className = css;
  return element;
}

export function button(label, action, value, css = 'small-button', symbol) {
  const element = el('button', undefined, css);
  element.type = 'button';
  element.dataset.action = action;
  if (value !== undefined) element.dataset.value = value;
  if (symbol) element.innerHTML = icon(symbol);
  element.append(document.createTextNode(label));
  return element;
}

export function safeUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol === 'https:' || url.protocol === 'http:') return url.href;
  } catch { /* A link may be relative or malformed. */ }
  return '';
}

export function markdown(value) {
  const output = el('div', undefined, 'markdown');
  output.innerHTML = DOMPurify.sanitize(marked.parse(value ?? '', { gfm: true, breaks: false }), {
    USE_PROFILES: { html: true }, FORBID_TAGS: ['img', 'video', 'audio', 'form', 'input', 'style', 'button', 'iframe'],
    FORBID_ATTR: ['style', 'srcset', 'formaction', 'id', 'name'],
  });
  for (const link of output.querySelectorAll('a')) {
    const url = safeUrl(link.getAttribute('href'));
    if (url) {
      link.href = url;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
    } else link.removeAttribute('href');
  }
  globalThis.renderMathInElement?.(output, {
    delimiters: [{ left: '$$', right: '$$', display: true }, { left: '\\[', right: '\\]', display: true },
      { left: '\\(', right: '\\)', display: false }, { left: '$', right: '$', display: false }],
    trust: false, throwOnError: false,
  });
  for (const code of output.querySelectorAll('pre > code')) {
    const raw = code.textContent;
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9) ?? '';
    if (raw.length <= 30000 && language && globalThis.hljs?.getLanguage(language)) globalThis.hljs.highlightElement(code);
    const pre = code.parentElement;
    const panel = el('div', undefined, 'code-panel');
    const header = el('div', undefined, 'code-header');
    header.append(el('span', language || '代码'), button('复制', 'copy-code', undefined, 'small-button', 'copy'));
    if (/^(html|svg|xml)$/.test(language) && /<\s*(?:html|svg|body|div|\!doctype)/i.test(raw)) {
      header.append(button('预览', 'preview-code', undefined, 'small-button'));
    }
    panel.raw = raw;
    panel.language = language;
    pre.replaceWith(panel);
    panel.append(header, pre);
  }
  return output;
}

export function staticPreview(source) {
  const safe = DOMPurify.sanitize(source, {
    WHOLE_DOCUMENT: true, USE_PROFILES: { html: true, svg: true, svgFilters: true },
    FORBID_TAGS: ['script', 'iframe', 'frame', 'object', 'embed', 'base', 'meta', 'link', 'form', 'input', 'foreignObject'],
    FORBID_ATTR: ['href', 'xlink:href', 'action', 'formaction', 'target', 'srcset'],
  });
  const frame = el('iframe', undefined, 'markup-preview');
  frame.title = '静态代码预览';
  frame.setAttribute('sandbox', '');
  frame.srcdoc = safe.replace(/<head>/i, '<head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; style-src \'unsafe-inline\';">');
  return frame;
}
