import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

import { parseApiBaseUrl } from '../env.ts';

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

const { ApiClientError, createApiUrl, fetchJson } = await import('./client.ts');

test('API 기본 주소는 로컬 절대 주소와 nginx 상대 경로를 지원한다', () => {
  assert.equal(parseApiBaseUrl(' http://127.0.0.1:8080/api/v1/ '), 'http://127.0.0.1:8080/api/v1');
  assert.equal(parseApiBaseUrl('/api/v1/'), '/api/v1');
  assert.equal(parseApiBaseUrl('/'), '/');
  for (const value of [
    '',
    'api/v1',
    '//example.com/api',
    'file:///api',
    '/api?x=1',
    '/api#x',
    'https://user:password@example.com/api',
    '/\\example.com/api',
  ]) {
    assert.throws(() => parseApiBaseUrl(value));
  }
});

test('endpoint 결합 시 API prefix와 검색어·반복 query를 보존한다', () => {
  const query = new URLSearchParams([
    ['q', '뉴스 & 장면'],
    ['tag', '서울'],
    ['tag', '부산'],
  ]);
  assert.equal(createApiUrl('/clips', '/api/v1'), '/api/v1/clips');
  assert.equal(createApiUrl('clips', '/'), '/clips');
  const url = new URL(createApiUrl('/clips?page=2', 'https://example.com/api/v1/', query));
  assert.equal(url.pathname, '/api/v1/clips');
  assert.equal(url.searchParams.get('page'), '2');
  assert.equal(url.searchParams.get('q'), '뉴스 & 장면');
  assert.deepEqual(url.searchParams.getAll('tag'), ['서울', '부산']);
});

test('다른 주소나 API prefix 바깥으로 나가는 endpoint는 거부한다', () => {
  for (const path of [
    'https://other.com/clips',
    '//other.com/clips',
    '../clips',
    '%2e%2e/clips',
    'clips#fragment',
    '\\other.com/clips',
  ]) {
    assert.throws(() => createApiUrl(path, '/api/v1'));
  }
});

test('JSON 요청은 옵션을 전달하고 성공 envelope의 data만 반환한다', async (context) => {
  const controller = new AbortController();
  const response = {
    isSuccess: true,
    code: 'COMM_200',
    message: 'Request succeeded',
    data: { id: '1' },
  };
  const fetch = context.mock.method(globalThis, 'fetch', async () => Response.json(response));
  assert.deepEqual(
    await fetchJson('/clips', {
      method: 'POST',
      body: { title: '뉴스' },
      signal: controller.signal,
      credentials: 'include',
      headers: { 'Idempotency-Key': 'registration-1' },
      query: new URLSearchParams({ page: '1' }),
    }),
    response.data,
  );
  const [url, init] = fetch.mock.calls[0].arguments;
  assert.equal(new URL(url, 'http://localhost').searchParams.get('page'), '1');
  assert.equal(init.method, 'POST');
  assert.equal(init.body, JSON.stringify({ title: '뉴스' }));
  assert.equal(init.headers.get('content-type'), 'application/json');
  assert.equal(init.headers.get('accept'), 'application/json');
  assert.equal(init.headers.get('idempotency-key'), 'registration-1');
  assert.equal(init.cache, 'no-store');
  assert.equal(init.credentials, 'include');
  assert.equal(init.signal, controller.signal);
});

test('백엔드 bigint memberId를 정밀도 손실 없이 십진 문자열로 보존한다', async (context) => {
  context.mock.method(
    globalThis,
    'fetch',
    async () =>
      new Response(
        '{"isSuccess":true,"code":"COMM_200","message":"OK","data":{"memberId":9223372036854775807,"loginId":"editor","role":"EDITOR","page":2}}',
      ),
  );
  const data = await fetchJson('/auth/me');
  assert.equal(data.memberId, '9223372036854775807');
  assert.equal(data.page, 2);
});

test('파일 요청은 FormData와 브라우저가 만드는 multipart boundary를 사용한다', async (context) => {
  const body = new FormData();
  body.append('video', new File(['video'], 'news.mp4', { type: 'video/mp4' }));
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: 'Request succeeded',
      data: { id: '1' },
    }),
  );
  await fetchJson('/clips', {
    method: 'POST',
    body,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  const [, init] = fetch.mock.calls[0].arguments;
  assert.equal(init.body, body);
  assert.equal(init.headers.has('content-type'), false);
});

test('204와 본문 없는 요청은 JSON 파싱·Content-Type을 요구하지 않는다', async (context) => {
  const fetch = context.mock.method(
    globalThis,
    'fetch',
    async () => new Response(null, { status: 204 }),
  );
  assert.equal(await fetchJson('/clips/1', { method: 'DELETE' }), undefined);
  const [, init] = fetch.mock.calls[0].arguments;
  assert.equal(init.body, undefined);
  assert.equal(init.headers.has('content-type'), false);
});

test('서버 한국어 메시지·코드·요청 ID를 보존하고 필드 오류는 진단 정보로 분리한다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json(
      {
        isSuccess: false,
        code: 'COMM_400',
        message: '입력을 확인해 주세요.',
        requestId: 'req_123',
        path: 'POST /internal/clips',
        data: { title: '필수 값' },
      },
      { status: 400 },
    ),
  );
  await assert.rejects(fetchJson('/clips'), (error) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.status, 400);
    assert.equal(error.code, 'COMM_400');
    assert.equal(error.message, '입력을 확인해 주세요.');
    assert.equal(error.requestId, 'req_123');
    assert.deepEqual(error.diagnostics.response.data, { title: '필수 값' });
    assert.equal(error.diagnostics.response.path, 'POST /internal/clips');
    assert.doesNotMatch(JSON.stringify(error), /internal|필수 값|diagnostics/);
    return true;
  });
});

test('성공 HTTP 상태 안의 명시적 실패도 오류로 처리한다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: false, code: 'FAILED' }),
  );
  await assert.rejects(fetchJson('/clips'), {
    name: 'ApiClientError',
    status: 200,
    code: 'FAILED',
  });
});

test('nginx HTML 오류와 잘못된 JSON 성공 응답을 구분한다', async (context) => {
  const fetch = context.mock.method(
    globalThis,
    'fetch',
    async () => new Response('<html>Bad Gateway</html>', { status: 502 }),
  );
  await assert.rejects(fetchJson('/clips'), {
    name: 'ApiClientError',
    status: 502,
    kind: 'invalid-response',
  });
  fetch.mock.mockImplementation(async () => new Response('invalid json'));
  await assert.rejects(fetchJson('/clips'), {
    name: 'ApiClientError',
    status: 200,
    kind: 'invalid-response',
  });
});

test('취소와 네트워크 오류를 정규화하며 재시도하거나 성공 응답으로 바꾸지 않는다', async (context) => {
  const controller = new AbortController();
  const aborted = new DOMException('cancelled', 'AbortError');
  controller.abort(aborted);
  const fetch = context.mock.method(globalThis, 'fetch', async () => {
    throw aborted;
  });
  await assert.rejects(fetchJson('/clips', { signal: controller.signal }), (error) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.kind, 'aborted');
    assert.equal(error.diagnostics.cause, aborted);
    return true;
  });
  const networkError = new TypeError('Failed to fetch');
  fetch.mock.mockImplementation(async () => {
    throw networkError;
  });
  await assert.rejects(fetchJson('/clips'), (error) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.code, 'CLIENT_NETWORK');
    assert.equal(error.status, 0);
    assert.equal(error.diagnostics.cause, networkError);
    assert.match(error.message, /네트워크 연결을 확인/);
    assert.doesNotMatch(error.message, /Failed to fetch/);
    return true;
  });
  assert.equal(fetch.mock.callCount(), 2);
});

test('성공 data의 빈 값·null·배열과 data 생략을 그대로 반환한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const data of [null, false, 0, '', [], { state: 'degraded' }, undefined]) {
    fetch.mock.mockImplementation(async () =>
      Response.json({ isSuccess: true, code: 'COMM_200', message: 'Request succeeded', data }),
    );
    assert.deepEqual(await fetchJson('/clips'), data);
  }
});

test('2xx라도 envelope가 없는 JSON이나 빈 본문은 성공으로 처리하지 않는다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const body of [
    'null',
    '[]',
    '{}',
    'true',
    '"text"',
    '{"data":[]}',
    '{"isSuccess":"true"}',
    '',
  ]) {
    fetch.mock.mockImplementation(async () => new Response(body));
    await assert.rejects(fetchJson('/clips'), {
      name: 'ApiClientError',
      code: 'CLIENT_INVALID_RESPONSE',
    });
  }
});

test('HTTP 실패는 성공 envelope나 임의 JSON 메시지에 속지 않는다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const body of [
    { isSuccess: true, code: 'COMM_200', message: '성공했습니다.', data: [] },
    { message: '서버 내부 예외', path: '/secret', data: [] },
  ]) {
    fetch.mock.mockImplementation(async () => Response.json(body, { status: 500 }));
    await assert.rejects(fetchJson('/clips'), (error) => {
      assert.equal(error.code, 'HTTP_500');
      assert.match(error.message, /요청을 처리하지 못했습니다/);
      return true;
    });
  }
});

test('서버 메시지가 없거나 사용자 메시지 계약이 깨지면 한국어 fallback을 쓴다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const message of [
    undefined,
    null,
    '',
    '  ',
    42,
    { detail: 'secret' },
    'java.lang.IllegalStateException: secret',
    '파일 오류 /srv/private/video.mp4',
    '파일 오류 C:\\private\\video.mp4',
    '<html>서버 내부 오류</html>',
    '{"오류":"secret"}',
  ]) {
    fetch.mock.mockImplementation(async () =>
      Response.json({ isSuccess: false, code: 'COMM_500', message }, { status: 500 }),
    );
    await assert.rejects(fetchJson('/clips'), (error) => {
      assert.equal(error.message, '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.');
      assert.equal(error.code, 'COMM_500');
      return true;
    });
  }
});

test('기존 백엔드의 영어 메시지를 번역하되 새 한국어 메시지는 코드와 무관하게 보존한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json(
      { isSuccess: false, code: 'COMM_401', message: 'Authentication is required' },
      { status: 401 },
    ),
  );
  await assert.rejects(fetchJson('/clips'), {
    message: '로그인이 필요합니다. 로그인 후 다시 시도해 주세요.',
  });
  fetch.mock.mockImplementation(async () =>
    Response.json(
      {
        isSuccess: false,
        code: 'COMM_401',
        message: '로그인 시간이 만료되었습니다. 다시 로그인해 주세요.',
      },
      { status: 401 },
    ),
  );
  await assert.rejects(fetchJson('/clips'), {
    message: '로그인 시간이 만료되었습니다. 다시 로그인해 주세요.',
  });
});

test('요청 ID는 본문을 우선하고 헤더로 보완하며 없는 ID는 만들지 않는다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch');
  for (const [bodyId, headerId, expected] of [
    ['body-1', 'header-1', 'body-1'],
    [undefined, 'header-1', 'header-1'],
    ['/internal/secret', 'header-1', 'header-1'],
    [undefined, undefined, undefined],
    [undefined, '<script>secret</script>', undefined],
  ]) {
    fetch.mock.mockImplementation(async () =>
      Response.json(
        { isSuccess: false, code: 'COMM_400', message: '입력을 확인해 주세요.', requestId: bodyId },
        { status: 400, headers: headerId ? { 'X-Request-ID': headerId } : {} },
      ),
    );
    await assert.rejects(fetchJson('/clips'), (error) => {
      assert.equal(error.requestId, expected);
      return true;
    });
  }
});

test('비정상 JSON과 본문 수신 중 실패에도 헤더 요청 ID를 유지한다', async (context) => {
  const fetch = context.mock.method(
    globalThis,
    'fetch',
    async () => new Response('bad json', { headers: { 'X-Request-ID': 'header-1' } }),
  );
  await assert.rejects(fetchJson('/clips'), { requestId: 'header-1', kind: 'invalid-response' });
  const response = new Response('{}', { headers: { 'X-Request-ID': 'header-2' } });
  context.mock.method(response, 'text', async () => {
    throw new TypeError('connection reset');
  });
  fetch.mock.mockImplementation(async () => response);
  await assert.rejects(fetchJson('/clips'), {
    requestId: 'header-2',
    kind: 'network',
    status: 200,
  });
});
