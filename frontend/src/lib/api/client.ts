import { env } from '@/lib/env';

interface JsonRequestInit extends Omit<RequestInit, 'body' | 'cache'> {
  body?: unknown;
  cache?: RequestCache;
}

function createApiUrl(path: string): URL {
  const baseUrl = new URL(env.apiBaseUrl);
  baseUrl.pathname = `${baseUrl.pathname.replace(/\/$/, '')}/`;

  return new URL(path.replace(/^\/+/, ''), baseUrl);
}

export async function fetchJson<ResponseData>(
  path: string,
  init: JsonRequestInit = {},
): Promise<ResponseData> {
  const { body, cache = 'no-store', headers, ...requestInit } = init;
  const requestHeaders = new Headers(headers);

  if (body !== undefined && !requestHeaders.has('content-type')) {
    requestHeaders.set('content-type', 'application/json');
  }

  const response = await fetch(createApiUrl(path), {
    ...requestInit,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache,
    headers: requestHeaders,
  });

  if (!response.ok) {
    throw new Error(`API request failed with HTTP status ${response.status}.`);
  }

  return response.json() as Promise<ResponseData>;
}
