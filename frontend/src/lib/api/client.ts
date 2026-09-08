import { env } from '@/lib/env';
import { ApiClientError, readRequestId } from '@/lib/api/error';

export { ApiClientError } from '@/lib/api/error';

// ApiResponse.java omits null fields, including data for bodyless successes.
export interface ApiResponse<Data> {
  isSuccess: boolean;
  code: string;
  message: string;
  data?: Data;
  timestamp?: string;
  path?: string;
  requestId?: string;
}

interface JsonRequestInit extends Omit<RequestInit, 'body' | 'cache'> {
  body?: unknown;
  cache?: RequestCache;
  query?: URLSearchParams;
}

export function createApiUrl(
  path: string,
  baseUrl = env.apiBaseUrl,
  query?: URLSearchParams,
): string {
  if (/^[a-z][a-z\d+.-]*:/i.test(path) || path.startsWith('//') || /[\\#]/.test(path)) {
    throw new Error('API endpoint must be a path relative to NEXT_PUBLIC_API_BASE_URL.');
  }

  const isRelative = baseUrl.startsWith('/');
  const base = new URL(`${baseUrl.replace(/\/+$/, '')}/`, 'http://api.local');
  const url = new URL(path.replace(/^\/+/, ''), base);

  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) {
    throw new Error('API endpoint must stay within NEXT_PUBLIC_API_BASE_URL.');
  }

  query?.forEach((value, key) => url.searchParams.append(key, value));
  return isRelative ? `${url.pathname}${url.search}` : url.href;
}

export async function fetchJson<ResponseData>(
  path: string,
  init: JsonRequestInit = {},
): Promise<ResponseData> {
  const { body, cache = 'no-store', headers, query, ...requestInit } = init;
  const requestHeaders = new Headers(headers);
  const isFormData = body instanceof FormData;

  if (!requestHeaders.has('accept')) requestHeaders.set('accept', 'application/json');

  if (isFormData) {
    // The browser supplies the multipart boundary with the file body.
    requestHeaders.delete('content-type');
  } else if (body !== undefined && !requestHeaders.has('content-type')) {
    requestHeaders.set('content-type', 'application/json');
  }

  const url = createApiUrl(path, env.apiBaseUrl, query);
  const options: RequestInit = {
    ...requestInit,
    body: isFormData ? body : body === undefined ? undefined : JSON.stringify(body),
    cache,
    headers: requestHeaders,
  };
  let response: Response;
  try {
    response = await fetch(url, options);
  } catch (cause) {
    throw new ApiClientError(requestInit.signal?.aborted ? 'aborted' : 'network', 0, {
      diagnostics: { cause },
    });
  }

  const headerRequestId = readRequestId(response.headers.get('x-request-id'));
  if (response.status === 204 || response.status === 205) return undefined as ResponseData;

  let text: string;
  try {
    text = await response.text();
  } catch (cause) {
    throw new ApiClientError(requestInit.signal?.aborted ? 'aborted' : 'network', response.status, {
      requestId: headerRequestId,
      diagnostics: { cause },
    });
  }
  let data: unknown;

  try {
    data = JSON.parse(text);
  } catch (cause) {
    throw new ApiClientError('invalid-response', response.status, {
      requestId: headerRequestId,
      diagnostics: { response: text, cause },
    });
  }

  const payload =
    data !== null && typeof data === 'object' && !Array.isArray(data)
      ? (data as Record<string, unknown>)
      : undefined;
  const requestId = readRequestId(payload?.requestId) ?? headerRequestId;
  const diagnostics = { response: data };

  if (!response.ok || payload?.isSuccess === false) {
    const isFailure = payload?.isSuccess === false;
    throw new ApiClientError(response.ok ? 'api' : 'http', response.status, {
      // Arbitrary JSON errors are not a trusted user-message envelope.
      message: isFailure ? payload.message : undefined,
      code: isFailure ? payload.code : undefined,
      requestId,
      diagnostics,
    });
  }

  if (
    payload?.isSuccess !== true ||
    typeof payload.code !== 'string' ||
    !payload.code.trim() ||
    typeof payload.message !== 'string'
  ) {
    throw new ApiClientError('invalid-response', response.status, { requestId, diagnostics });
  }

  return payload.data as ResponseData;
}
