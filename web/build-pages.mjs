import { cp, mkdir, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assetDirectories, assets, csp } from './assets.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const output = join(root, 'dist');
const modules = join(root, 'node_modules');
const fileLimit = 25 * 1024 * 1024;

await rm(output, { recursive: true, force: true });
await cp(join(root, 'public'), output, { recursive: true });
for (const [url, source] of Object.entries(assets)) {
  const target = join(output, url.slice(1));
  await mkdir(dirname(target), { recursive: true });
  await cp(join(modules, source), target);
}
for (const [target, packagePath] of Object.entries(assetDirectories)) {
  await cp(join(modules, packagePath), join(output, 'vendor', target), { recursive: true });
}

await writeFile(join(output, 'runtime.mjs'), 'export const hosted = true;\n');
await writeFile(join(output, '_headers'), [
  '/*',
  '  Content-Security-Policy: ' + csp,
  '  X-Content-Type-Options: nosniff',
  '  Referrer-Policy: no-referrer',
  '',
  '/vendor/*',
  '  Cache-Control: public, max-age=3600',
  '',
  '/*.mjs',
  '  Content-Type: text/javascript; charset=utf-8',
  '',
].join('\n'));
await writeFile(join(output, '_routes.json'), JSON.stringify({ version: 1, include: ['/api/*'], exclude: [] }) + '\n');

async function assetCount(directory) {
  let count = 0;
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) count += await assetCount(path);
    else {
      if ((await stat(path)).size > fileLimit) throw new Error(path + ' 超过 Pages 的 25 MiB 单文件上限。');
      count++;
    }
  }
  return count;
}
const count = await assetCount(output);
if (count > 20000) throw new Error('静态资源数量超过 Pages 免费套餐的 20,000 文件上限。');
console.log('Aera Pages · dist/ · ' + count + ' 个文件');
