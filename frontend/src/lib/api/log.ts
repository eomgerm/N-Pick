import type { ApiClientError } from '@/lib/api/error';

export const API_LOG_ENDPOINT = '/client-logs';
export const MAX_API_LOG_BYTES = 24 * 1024;

export interface ApiLogEntry {
  method: string;
  endpoint: string;
  status: number;
  durationMs: number;
  requestId?: string;
  outcome: string;
  code?: string;
  response?: unknown;
}

const privateField =
  /password|passwd|secret|token|cookie|authorization|apikey|session|path|url|uri|querytext|originalquery|description|transcript|subtitle|script|ocr|caption|raw|prompt|resolutionreason/i;

export function redactResponse(value: unknown, key = '', depth = 0): unknown {
  const field = key.replace(/[_-]/g, '');
  if (
    privateField.test(field) ||
    /^(q|query|text|content|value|message|stack|trace)$/i.test(field)
  ) {
    return '[REDACTED]';
  }
  if (depth >= 12) return '[TRUNCATED]';
  if (Array.isArray(value)) return value.map((item) => redactResponse(item, key, depth + 1));
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([name, item]) => [name, redactResponse(item, name, depth + 1)]),
    );
  }
  if (typeof value === 'string') {
    // Unknown fields can still contain diagnostic paths or signed media URLs.
    if (/[a-z]:[\\/]|https?:\/\/|(?:^|\s)\/\S+|\bBearer\s/i.test(value)) return '[REDACTED]';
    return value.length > 200 ? `${value.slice(0, 200)}… [TRUNCATED]` : value;
  }
  return value;
}

export function writeServerApiLog(entry: ApiLogEntry, source: 'browser' | 'server'): void {
  const line = `[API] ${JSON.stringify({
    ...entry,
    source,
    loggedAt: new Date().toISOString(),
  })}`;
  if (entry.outcome !== 'success' && entry.outcome !== 'aborted') console.error(line);
  else console.info(line);
}

function forwardApiLog(entry: ApiLogEntry): void {
  let body = JSON.stringify(entry);
  if (new TextEncoder().encode(body).byteLength > MAX_API_LOG_BYTES) {
    body = JSON.stringify({ ...entry, response: '[OMITTED: response exceeds log size limit]' });
  }
  // Use native fetch so reporting does not recursively report itself. A failed
  // log delivery must neither block nor retry the user's original API request.
  void fetch(API_LOG_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    credentials: 'omit',
    redirect: 'error',
    keepalive: true,
    signal: AbortSignal.timeout(5_000),
  }).catch(() => {});
}

export function logApiResponse({
  url,
  method,
  startedAt,
  status,
  requestId,
  response,
  error,
}: {
  url: string;
  method: string;
  startedAt: number;
  status?: number;
  requestId?: string;
  response?: unknown;
  error?: ApiClientError;
}): void {
  // Logging must never turn a completed API request into a failed request.
  try {
    const endpoint = new URL(url, 'http://api.local').pathname;
    const outcome = error?.kind ?? 'success';
    const entry: ApiLogEntry = {
      method,
      endpoint,
      status: status ?? 0,
      durationMs: Math.round(performance.now() - startedAt),
      requestId: error?.requestId ?? requestId,
      outcome,
      ...(error ? { code: error.code } : {}),
      response: redactResponse(response),
    };
    if (typeof window === 'undefined') {
      writeServerApiLog(entry, 'server');
      return;
    }
    forwardApiLog(entry);
    const label = `[API] ${method} ${endpoint} ${entry.status} ${outcome}`;
    if (error && error.kind !== 'aborted') console.error(label, entry);
    else console.info(label, entry);
  } catch {
    // Console integrations may throw; preserve the original response/error.
  }
}
