export const assets = {
  '/vendor/marked.js': 'marked/lib/marked.esm.js',
  '/vendor/purify.js': 'dompurify/dist/purify.es.mjs',
  '/vendor/fflate.js': 'fflate/esm/browser.js',
  '/vendor/katex.js': 'katex/dist/katex.min.js',
  '/vendor/auto-render.js': 'katex/dist/contrib/auto-render.min.js',
  '/vendor/katex.css': 'katex/dist/katex.min.css',
  '/vendor/highlight.js': '@highlightjs/cdn-assets/highlight.min.js',
  '/vendor/highlight.css': '@highlightjs/cdn-assets/styles/github.min.css',
  '/vendor/pdf.mjs': 'pdfjs-dist/build/pdf.mjs',
  '/vendor/pdf.worker.mjs': 'pdfjs-dist/build/pdf.worker.mjs',
};
export const assetDirectories = {
  fonts: 'katex/dist/fonts',
  cmaps: 'pdfjs-dist/cmaps',
  standard_fonts: 'pdfjs-dist/standard_fonts',
  wasm: 'pdfjs-dist/wasm',
};
export const csp = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
