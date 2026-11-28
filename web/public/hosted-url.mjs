export function publicHttps(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('请输入有效的 API 地址。'); }
  const host = url.hostname.replace(/\.$/, '');
  const labels = host.split('.');
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      labels.length < 2 || !/^[a-z][a-z0-9-]+$/i.test(labels.at(-1)) ||
      /(?:^|\.)(localhost|local|internal|lan|home|onion|invalid|test)$/i.test(host) ||
      !labels.every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))) {
    throw new Error('Cloudflare 网页版需要可从公网访问的 HTTPS API 地址。本机或局域网接口可在本地 Node.js 版使用。');
  }
  return url;
}
