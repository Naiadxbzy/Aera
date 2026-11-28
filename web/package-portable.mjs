import { cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assetDirectories, assets } from './assets.mjs';

if (process.platform !== 'win32') throw new Error('Build the Windows package with a Windows Node.js runtime.');

const web = dirname(fileURLToPath(import.meta.url));
const target = join(web, '..', 'portable_web');
const app = join(target, 'app');
const runtime = join(target, 'runtime');
const modules = join(web, 'node_modules');
const launcher = 'Aera-Web-Portable.bat';

async function licenseForNode() {
  for (const file of ['LICENSE', 'LICENSE.txt']) {
    try {
      return await readFile(join(dirname(process.execPath), file), 'utf8');
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
  const url = 'https://raw.githubusercontent.com/nodejs/node/' + process.version + '/LICENSE';
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error('Cannot fetch the Node.js license: HTTP ' + response.status);
  const license = await response.text();
  if (!license.startsWith('Node.js is licensed for use as follows:')) throw new Error('Unexpected Node.js license text.');
  return license;
}

async function copy(source, destination) {
  await mkdir(dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true });
}

async function copyVendorAssets() {
  const vendorPaths = Object.values(assets);
  const packages = new Set(vendorPaths.map(path => path.split('/').slice(0, path.startsWith('@') ? 2 : 1).join('/')));
  for (const packageName of packages) {
    const entries = await readdir(join(modules, packageName), { withFileTypes: true });
    for (const entry of entries.filter(item => item.isFile() && /^(licen[cs]e|notice)([.-]|$)/i.test(item.name))) {
      vendorPaths.push(packageName + '/' + entry.name);
    }
  }
  for (const path of [...vendorPaths, ...Object.values(assetDirectories)]) {
    await copy(join(modules, path), join(app, 'node_modules', path));
  }
}

const license = await licenseForNode();
await mkdir(target, { recursive: true });
await writeFile(join(target, '.gitignore'), '*\n');
await copy(process.execPath, join(runtime, 'node.exe'));
await writeFile(join(runtime, 'LICENSE.txt'), license);
await copy(join(web, 'public'), join(app, 'public'));
for (const name of ['server.mjs', 'assets.mjs', 'generation-core.mjs', 'protocols.mjs', 'stream.mjs']) {
  await copy(join(web, name), join(app, name));
}
await copyVendorAssets();
await copy(join(web, 'portable', 'launch.mjs'), join(app, 'launch.mjs'));
await copy(join(web, 'portable', launcher), join(target, launcher));
console.log('Aera Web Portable · Windows ' + process.arch + ' · Node.js ' + process.version);
console.log(join(target, launcher));
