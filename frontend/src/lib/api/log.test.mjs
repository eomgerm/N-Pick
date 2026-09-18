import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/lib/')
        ? new URL(`../${specifier.slice('@/lib/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});
const { fetchJson } = await import('./client.ts');
const { receiveApiLog } = await import('./log-receiver.ts');
const { MAX_API_LOG_BYTES } = await import('./log.ts');
const readEntry = (call) => JSON.parse(call.arguments[0].slice('[API] '.length));

function logRequest(entry, headers = {}) {
  return new Request('http://localhost:3000/client-logs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Origin: 'http://localhost:3000',
      Host: 'localhost:3000',
      'Sec-Fetch-Site': 'same-origin',
      ...headers,
    },
    body: JSON.stringify(entry),
  });
}

const browserEntry = {
  method: 'GET',
  endpoint: '/api/v1/clips',
  status: 200,
  durationMs: 12,
  outcome: 'success',
  requestId: 'req-1',
  response: { data: { status: 'ready', password: 'private' } },
};

test('브라우저 응답은 같은 오리진 서버에 마스킹하여 한 번 전달하고 한 줄 서버 로그로 남긴다', async (context) => {
  globalThis.window = {};
  context.after(() => {
    delete globalThis.window;
  });
  const info = context.mock.method(console, 'info', () => {});
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: 'OK',
      data: { password: 'private', count: 2 },
    }),
  );
  assert.deepEqual(await fetchJson('/clips'), { password: 'private', count: 2 });
  assert.equal(fetch.mock.callCount(), 2);
  const [url, options] = fetch.mock.calls[1].arguments;
  assert.equal(url, '/client-logs');
  assert.equal(options.credentials, 'omit');
  assert.equal(options.keepalive, true);
  assert.equal(options.redirect, 'error');
  assert.doesNotMatch(options.body, /private/);
  const response = await receiveApiLog(logRequest(JSON.parse(options.body)));
  assert.equal(response.status, 204);
  const entry = readEntry(info.mock.calls.at(-1));
  assert.equal(entry.source, 'browser');
  assert.equal(entry.response.data.count, 2);
  assert.equal(entry.response.data.password, '[REDACTED]');
  assert.equal(fetch.mock.callCount(), 2);
});

test('로그 전송 실패와 큰 응답은 원래 API 결과를 바꾸거나 재전송하지 않는다', async (context) => {
  globalThis.window = {};
  context.after(() => {
    delete globalThis.window;
  });
  context.mock.method(console, 'info', () => {});
  const data = Array.from({ length: 1000 }, () => ({ status: 'ready'.repeat(30) }));
  const fetch = context.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/client-logs') throw new Error('log server unavailable');
    return Response.json({ isSuccess: true, code: 'COMM_200', message: 'OK', data });
  });
  assert.deepEqual(await fetchJson('/clips'), data);
  assert.equal(fetch.mock.callCount(), 2);
  const body = fetch.mock.calls[1].arguments[1].body;
  assert.ok(new TextEncoder().encode(body).byteLength <= MAX_API_LOG_BYTES);
  assert.match(JSON.parse(body).response, /OMITTED/);
});

test('서버 수신부는 외부 요청·잘못된 입력·초과 본문을 로그 없이 거부한다', async (context) => {
  const info = context.mock.method(console, 'info', () => {});
  for (const headers of [
    { Origin: 'https://external.example' },
    { 'Sec-Fetch-Site': 'cross-site' },
    { Origin: '' },
  ]) {
    assert.equal((await receiveApiLog(logRequest(browserEntry, headers))).status, 403);
  }
  assert.equal(
    (await receiveApiLog(logRequest(browserEntry, { 'Content-Type': 'text/plain' }))).status,
    415,
  );
  for (const entry of [
    null,
    {},
    { ...browserEntry, endpoint: '/clips?password=private' },
    { ...browserEntry, method: 'GET\nforged' },
    { ...browserEntry, durationMs: -1 },
    { ...browserEntry, outcome: 'forged' },
    { ...browserEntry, status: 999 },
  ]) {
    assert.equal((await receiveApiLog(logRequest(entry))).status, 400);
  }
  assert.equal(
    (
      await receiveApiLog(
        logRequest(browserEntry, { 'Content-Length': String(MAX_API_LOG_BYTES + 1) }),
      )
    ).status,
    413,
  );
  assert.equal(
    (await receiveApiLog(logRequest({ ...browserEntry, response: '한'.repeat(MAX_API_LOG_BYTES) })))
      .status,
    413,
  );
  assert.equal(info.mock.callCount(), 0);
});

test('서버는 수신 데이터도 다시 마스킹하고 클라이언트가 서버 출처나 시각을 위조하지 못한다', async (context) => {
  const error = context.mock.method(console, 'error', () => {});
  assert.equal(
    (
      await receiveApiLog(
        logRequest({
          ...browserEntry,
          outcome: 'http',
          status: 500,
          source: 'server',
          loggedAt: 'forged',
          headers: { Cookie: 'private' },
          response: { token: 'private', path: '/srv/private', message: 'private', count: 1 },
        }),
      )
    ).status,
    204,
  );
  const entry = readEntry(error.mock.calls[0]);
  assert.equal(entry.source, 'browser');
  assert.notEqual(entry.loggedAt, 'forged');
  assert.equal(entry.response.count, 1);
  assert.doesNotMatch(error.mock.calls[0].arguments[0], /private|Cookie/);
  assert.equal(error.mock.callCount(), 1);
});

test('성공마다 경로·상태·시간·요청 ID와 마스킹한 응답 snapshot을 기록한다', async (context) => {
  const info = context.mock.method(console, 'info', () => {});
  const data = {
    status: 'succeeded',
    items: [{ sceneId: '123', score: 0.8, description: 'private description' }],
    password: 'private password',
    access_token: 'private token',
    apiKey: 'private key',
    query_text: 'private query',
    transcript: { text: 'private transcript' },
    preview_url: 'https://media.local/clip?token=private',
    debug: '/srv/private/file',
  };
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json(
      { isSuccess: true, code: 'COMM_200', message: 'OK', requestId: 'body-1', data },
      { headers: { 'X-Request-ID': 'header-1', 'Set-Cookie': 'private cookie' } },
    ),
  );
  const result = await fetchJson('/search?query=private', {
    method: 'POST',
    body: { password: 'private request password' },
    headers: { Authorization: 'Bearer private authorization' },
  });
  assert.deepEqual(result, data);
  assert.equal(info.mock.callCount(), 1);
  const entry = readEntry(info.mock.calls[0]);
  assert.equal(entry.source, 'server');
  assert.ok(!Number.isNaN(Date.parse(entry.loggedAt)));
  assert.equal(entry.method, 'POST');
  assert.ok(entry.endpoint.endsWith('/search'));
  assert.equal(entry.status, 200);
  assert.ok(entry.durationMs >= 0);
  assert.equal(entry.requestId, 'body-1');
  assert.equal(entry.response.data.items[0].sceneId, '123');
  assert.equal(entry.response.data.items[0].score, 0.8);
  assert.doesNotMatch(JSON.stringify(info.mock.calls[0].arguments), /private|Bearer/);
  result.items[0].score = 1;
  assert.equal(entry.response.data.items[0].score, 0.8);
});

test('204·205 응답도 한 번씩 기록하며 헤더 요청 ID를 보존한다', async (context) => {
  const info = context.mock.method(console, 'info', () => {});
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const status of [204, 205]) {
    fetch.mock.mockImplementation(
      async () => new Response(null, { status, headers: { 'X-Request-ID': 'empty-1' } }),
    );
    assert.equal(await fetchJson('/auth/logout', { method: 'POST' }), undefined);
    const entry = readEntry(info.mock.calls.at(-1));
    assert.equal(entry.status, status);
    assert.equal(entry.requestId, 'empty-1');
    assert.equal(entry.outcome, 'success');
  }
  assert.equal(info.mock.callCount(), 2);
});

test('HTTP·업무·JSON·본문 수신·네트워크 오류는 원래 오류를 유지하며 한 번씩 기록한다', async (context) => {
  const errorLog = context.mock.method(console, 'error', () => {});
  const info = context.mock.method(console, 'info', () => {});
  const fetch = context.mock.method(globalThis, 'fetch');
  const unreadable = new Response('{}', { headers: { 'X-Request-ID': 'stream-1' } });
  context.mock.method(unreadable, 'text', async () => {
    throw new Error('private stream error');
  });
  const cases = [
    [() => Response.json({ isSuccess: false, code: 'COMM_403' }, { status: 403 }), 'http', 403],
    [() => Response.json({ isSuccess: false, code: 'FAILED' }), 'api', 200],
    [
      () => new Response('<html>private diagnostic</html>', { status: 502 }),
      'invalid-response',
      502,
    ],
    [() => Response.json({ data: {} }), 'invalid-response', 200],
    [() => unreadable, 'network', 200],
    [
      () => {
        throw new TypeError('private network error');
      },
      'network',
      0,
    ],
  ];
  for (const [respond, kind, status] of cases) {
    fetch.mock.mockImplementation(async () => respond());
    await assert.rejects(fetchJson('/clips'), { kind, status });
    const entry = readEntry(errorLog.mock.calls.at(-1));
    assert.equal(entry.outcome, kind);
    assert.equal(entry.status, status);
  }
  assert.equal(errorLog.mock.callCount(), cases.length);
  assert.equal(info.mock.callCount(), 0);
  assert.doesNotMatch(JSON.stringify(errorLog.mock.calls.map((call) => call.arguments)), /private/);
});

test('취소는 info로 기록하고 console 실패가 API 성공·실패를 바꾸지 않는다', async (context) => {
  const info = context.mock.method(console, 'info', () => {});
  const fetch = context.mock.method(globalThis, 'fetch', async () => {
    throw new DOMException('cancelled', 'AbortError');
  });
  await assert.rejects(fetchJson('/search', { signal: AbortSignal.abort() }), { kind: 'aborted' });
  assert.equal(readEntry(info.mock.calls[0]).outcome, 'aborted');

  info.mock.mockImplementation(() => {
    throw new Error('console failed');
  });
  context.mock.method(console, 'error', () => {
    throw new Error('console failed');
  });
  fetch.mock.mockImplementation(async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: 'OK', data: { id: '1' } }),
  );
  assert.deepEqual(await fetchJson('/clips'), { id: '1' });
  fetch.mock.mockImplementation(async () => new Response(null, { status: 500 }));
  await assert.rejects(fetchJson('/clips'), { status: 500, kind: 'invalid-response' });
});
