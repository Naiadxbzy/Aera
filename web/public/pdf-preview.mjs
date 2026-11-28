import { openPdf } from './files.mjs';
import { button, el } from './ui.mjs';

export async function previewPdf(blob, view, dialog) {
  const pdf = await openPdf(blob);
  if (!view.isConnected) { await pdf.destroy(); return; }
  const toolbar = el('div', undefined, 'pdf-controls');
  const canvas = el('canvas', undefined, 'pdf-canvas');
  canvas.setAttribute('role', 'img');
  const back = button('上一页', ''), next = button('下一页', '');
  const count = el('span');
  toolbar.append(back, count, next);
  view.append(toolbar, canvas);
  let index = 1;
  let closed = false;

  async function show() {
    back.disabled = next.disabled = true;
    count.textContent = '第 ' + index + ' 页 / 共 ' + pdf.numPages + ' 页';
    canvas.setAttribute('aria-label', count.textContent);
    const page = await pdf.getPage(index);
    const base = page.getViewport({ scale: 1 });
    const viewport = page.getViewport({ scale: Math.min(2, 1200 / Math.max(base.width, base.height)) });
    canvas.width = Math.ceil(viewport.width);
    canvas.height = Math.ceil(viewport.height);
    await page.render({ canvas, viewport, background: '#ffffff' }).promise;
    back.disabled = index === 1;
    next.disabled = index === pdf.numPages;
  }

  const visit = step => {
    index += step;
    void show().catch(error => {
      if (!closed) count.textContent = '页面无法预览：' + error.message;
    });
  };
  back.addEventListener('click', () => visit(-1));
  next.addEventListener('click', () => visit(1));
  dialog.addEventListener('close', () => {
    closed = true;
    void pdf.destroy();
  }, { once: true });
  await show();
}
