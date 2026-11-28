import { spawn } from 'node:child_process';
import { createAeraServer } from './server.mjs';

const host = '127.0.0.1';
const preferredPort = 4173;

function listen(server, port) {
  return new Promise((resolve, reject) => {
    function failed(error) {
      server.off('listening', ready);
      reject(error);
    }
    function ready() {
      server.off('error', failed);
      resolve();
    }
    server.once('error', failed);
    server.once('listening', ready);
    server.listen(port, host);
  });
}

async function start() {
  const configuredPort = process.env.AERA_PORT;
  const first = Number(configuredPort ?? preferredPort);
  if (!Number.isInteger(first) || first < 0 || first > 65535) {
    throw new Error('AERA_PORT must be an integer from 0 to 65535.');
  }
  const last = configuredPort === undefined ? preferredPort + 10 : first;
  const server = createAeraServer();
  for (let port = first; port <= last; port++) {
    try {
      await listen(server, port);
      break;
    } catch (error) {
      if (port === last || !['EADDRINUSE', 'EACCES'].includes(error.code)) {
        throw new Error('Cannot use port ' + port + ' (' + (error.code ?? error.message) + ').');
      }
    }
  }

  const url = 'http://' + host + ':' + server.address().port;
  console.log('Aera Web: ' + url);
  console.log('Keep this window open. Press Ctrl+C to stop.');
  if (process.env.AERA_NO_BROWSER !== '1') {
    const open = spawn(process.env.ComSpec || 'cmd.exe',
      ['/d', '/c', 'start', '""', url], { windowsVerbatimArguments: true, windowsHide: true, stdio: 'ignore' });
    open.on('error', () => console.log('Open this address in your browser: ' + url));
    open.on('exit', code => {
      if (code !== 0) console.log('Open this address in your browser: ' + url);
    });
  }
}

try {
  await start();
} catch (error) {
  console.error('Aera Web could not start: ' + error.message);
  process.exitCode = 1;
}
