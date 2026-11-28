import { pagesApi } from '../../cloudflare/api.mjs';

export function onRequest({ request, env }) {
  return pagesApi(request, env);
}
