import { readRequestId } from '@/lib/api/error';
import { MAX_API_LOG_BYTES, redactResponse, writeServerApiLog } from '@/lib/api/log';
import type { ApiLogEntry } from '@/lib/api/log';

const outcomes = new Set(['success', 'http', 'api', 'network', 'invalid-response', 'aborted']);

function parseEntry(value: unknown): ApiLogEntry | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  const entry = value as Record<string, unknown>;
  if (
    typeof entry.method !== 'string' ||
    !/^(GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE)$/.test(entry.method) ||
    typeof entry.endpoint !== 'string' ||
    !/^\/[a-z\d/_%.-]{0,299}$/i.test(entry.endpoint) ||
    typeof entry.status !== 'number' ||
    !Number.isInteger(entry.status) ||
    (entry.status !== 0 && (entry.status < 100 || entry.status > 599)) ||
    typeof entry.durationMs !== 'number' ||
    !Number.isFinite(entry.durationMs) ||
    entry.durationMs < 0 ||
    typeof entry.outcome !== 'string' ||
    !outcomes.has(entry.outcome)
  )
    return;

  // Browser reports are untrusted: select only the supported fields and mask
  // the response again before writing it to the server's stdout/stderr.
  return {
    method: entry.method,
    endpoint: entry.endpoint,
    status: entry.status,
    durationMs: Math.round(entry.durationMs),
    requestId: readRequestId(entry.requestId),
    outcome: entry.outcome,
    code:
      typeof entry.code === 'string' && /^[A-Z][A-Z\d_.-]{0,99}$/.test(entry.code)
        ? entry.code
        : undefined,
    response: redactResponse(entry.response),
  };
}

export async function receiveApiLog(request: Request): Promise<Response> {
  // This endpoint intentionally accepts login failures too, so it cannot require
  // a session. Only same-origin browser JSON submissions are accepted.
  const origin = request.headers.get('origin');
  if (request.headers.get('sec-fetch-site') !== 'same-origin' || !origin) {
    return new Response(null, { status: 403 });
  }
  try {
    if (new URL(origin).host !== request.headers.get('host')) {
      return new Response(null, { status: 403 });
    }
  } catch {
    return new Response(null, { status: 403 });
  }
  if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') {
    return new Response(null, { status: 415 });
  }
  if (Number(request.headers.get('content-length')) > MAX_API_LOG_BYTES) {
    return new Response(null, { status: 413 });
  }

  const reader = request.body?.getReader();
  if (!reader) return new Response(null, { status: 400 });
  let text = '';
  let size = 0;
  const decoder = new TextDecoder();
  let entry: ApiLogEntry | undefined;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_API_LOG_BYTES) {
        await reader.cancel();
        return new Response(null, { status: 413 });
      }
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
    entry = parseEntry(JSON.parse(text));
  } catch {
    return new Response(null, { status: 400 });
  } finally {
    reader.releaseLock();
  }
  if (!entry) return new Response(null, { status: 400 });
  writeServerApiLog(entry, 'browser');
  return new Response(null, { status: 204 });
}
