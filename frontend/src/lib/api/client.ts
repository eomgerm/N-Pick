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
  idempotencyKey?: string;
  query?: URLSearchParams;
}

interface JsonParseContext {
  source?: string;
}

const parseJsonWithSource = JSON.parse as unknown as (
  text: string,
  reviver: (this: unknown, key: string, value: unknown, context?: JsonParseContext) => unknown,
) => unknown;

function parseApiJson(text: string): unknown {
  return parseJsonWithSource(text, (key, value, context) => {
    if (key === 'memberId' && typeof value === 'number') {
      if (typeof context?.source === 'string' && /^-?\d+$/.test(context.source)) {
        return context.source;
      }
      // Older engines omit reviver source; only stringify values known to retain exact precision.
      if (Number.isSafeInteger(value)) return String(value);
    }
    return value;
  });
}

let csrfReady = false;
let csrfPreparation: Promise<void> | undefined;

function readCsrfCookie(): string | undefined {
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='));
  if (!value) return undefined;
  try {
    return decodeURIComponent(value.slice('XSRF-TOKEN='.length)) || undefined;
  } catch {
    return undefined;
  }
}

async function prepareCsrf(): Promise<string> {
  if (!csrfReady || !readCsrfCookie()) {
    csrfPreparation ??= fetchJson<void>('/auth/csrf', { signal: AbortSignal.timeout(10_000) })
      .then(() => {
        csrfReady = true;
      })
      .finally(() => {
        csrfPreparation = undefined;
      });
    await csrfPreparation;
  }
  const token = readCsrfCookie();
  if (!token) {
    csrfReady = false;
    throw new ApiClientError('api', 0, {
      code: 'CLIENT_CSRF_UNAVAILABLE',
      message: '요청 보안 쿠키를 확인할 수 없습니다. 쿠키 허용 여부와 접속 주소를 확인해 주세요.',
    });
  }
  return token;
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
  baseUrl = env.apiBaseUrl,
): Promise<ResponseData> {
  const { body, cache = 'no-store', headers, idempotencyKey, query, ...requestInit } = init;
  const requestHeaders = new Headers(headers);
  const isMutation = !['GET', 'HEAD', 'OPTIONS'].includes(
    (requestInit.method ?? 'GET').toUpperCase(),
  );
  const isFormData = body instanceof FormData;

  if (idempotencyKey !== undefined) requestHeaders.set('Idempotency-Key', idempotencyKey);
  if (!isMutation && requestHeaders.has('Idempotency-Key')) {
    throw new Error('Idempotency-Key is only supported for mutation requests.');
  }

  if (!requestHeaders.has('accept')) requestHeaders.set('accept', 'application/json');

  if (isFormData) {
    // The browser supplies the multipart boundary with the file body.
    requestHeaders.delete('content-type');
  } else if (body !== undefined && !requestHeaders.has('content-type')) {
    requestHeaders.set('content-type', 'application/json');
  }

  const url = createApiUrl(path, baseUrl, query);
  if (typeof document !== 'undefined') {
    if (baseUrl !== env.apiBaseUrl) throw new Error('Browser requests must use the public API.');
    if (isMutation) {
      if (requestInit.signal?.aborted) throw new ApiClientError('aborted', 0);
      requestHeaders.set('X-XSRF-TOKEN', await prepareCsrf());
      if (requestInit.signal?.aborted) throw new ApiClientError('aborted', 0);
    }
  }
  const options: RequestInit = {
    ...requestInit,
    credentials: 'include',
    // Do not forward session cookies or CSRF headers through an API redirect.
    redirect: 'error',
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

  // The backend currently uses the same 403 for CSRF and role denial. Prepare
  // a fresh token on the NEXT manual attempt; never replay a mutation here.
  if (response.status === 403) csrfReady = false;

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
    data = parseApiJson(text);
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
