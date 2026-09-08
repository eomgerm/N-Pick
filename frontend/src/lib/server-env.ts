import 'server-only';

import { parseApiBaseUrl } from '@/lib/env';

export function internalApiBaseUrl(): string {
  const url = parseApiBaseUrl(process.env.API_INTERNAL_BASE_URL ?? 'http://127.0.0.1:8080/api/v1');
  if (url.startsWith('/'))
    throw new Error('API_INTERNAL_BASE_URL must be an absolute HTTP(S) URL.');
  return url;
}
