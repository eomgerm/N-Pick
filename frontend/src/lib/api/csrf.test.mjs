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
const success = () => Response.json({ isSuccess: true, code: 'COMM_200', message: 'OK' });

test('브라우저 변경 요청은 CSRF를 준비하고 현재 쿠키를 전송하되 GET에는 붙이지 않는다', async (context) => {
  globalThis.document = { cookie: '' };
  context.after(() => {
    delete globalThis.document;
  });
  const mock = context.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(options.credentials, 'include');
    if (url.endsWith('/auth/csrf')) document.cookie = 'XSRF-TOKEN=first%2Dtoken';
    return success();
  });
  await Promise.all([
    fetchJson('/auth/login', { method: 'POST', body: { loginId: 'test' } }),
    fetchJson('/clips', { method: 'POST' }),
  ]);
  assert.equal(
    mock.mock.calls.filter((call) => call.arguments[0].endsWith('/auth/csrf')).length,
    1,
  );
  for (const call of mock.mock.calls.filter((call) => !call.arguments[0].endsWith('/auth/csrf'))) {
    assert.equal(call.arguments[1].headers.get('x-xsrf-token'), 'first-token');
  }
  document.cookie = 'XSRF-TOKEN=rotated-token';
  await fetchJson('/clips/1', { method: 'PATCH', body: {} });
  assert.equal(mock.mock.calls.at(-1).arguments[1].headers.get('x-xsrf-token'), 'rotated-token');
  await fetchJson('/auth/me');
  assert.equal(mock.mock.calls.at(-1).arguments[1].headers.has('x-xsrf-token'), false);
});

test('403은 변경 요청을 재전송하지 않고 다음 수동 요청에서 CSRF를 갱신한다', async (context) => {
  globalThis.document = { cookie: 'XSRF-TOKEN=stale-token' };
  context.after(() => {
    delete globalThis.document;
  });
  let shouldFail = true;
  const mock = context.mock.method(globalThis, 'fetch', async (url) => {
    if (url.endsWith('/auth/csrf')) {
      document.cookie = 'XSRF-TOKEN=fresh-token';
      return success();
    }
    return shouldFail
      ? Response.json(
          { isSuccess: false, code: 'COMM_403', message: 'Access is denied' },
          { status: 403 },
        )
      : success();
  });
  await assert.rejects(fetchJson('/auth/logout', { method: 'POST' }), { status: 403 });
  assert.equal(
    mock.mock.calls.filter((call) => call.arguments[0].endsWith('/auth/logout')).length,
    1,
  );
  shouldFail = false;
  await fetchJson('/auth/logout', { method: 'POST' });
  assert.ok(mock.mock.calls.at(-2).arguments[0].endsWith('/auth/csrf'));
  assert.equal(mock.mock.calls.at(-1).arguments[1].headers.get('x-xsrf-token'), 'fresh-token');
});

test('CSRF 쿠키가 없으면 로그인·업로드를 보내지 않는다', async (context) => {
  globalThis.document = { cookie: '' };
  context.after(() => {
    delete globalThis.document;
  });
  const mock = context.mock.method(globalThis, 'fetch', async () => success());
  await assert.rejects(fetchJson('/auth/login', { method: 'POST' }), {
    code: 'CLIENT_CSRF_UNAVAILABLE',
  });
  assert.equal(mock.mock.callCount(), 1);
  assert.ok(mock.mock.calls[0].arguments[0].endsWith('/auth/csrf'));
});

test('CSRF 준비 중 취소한 변경 요청은 전송하지 않으며 FormData boundary를 보존한다', async (context) => {
  globalThis.document = { cookie: '' };
  context.after(() => {
    delete globalThis.document;
  });
  const controller = new AbortController();
  const mock = context.mock.method(globalThis, 'fetch', async (url) => {
    if (url.endsWith('/auth/csrf')) {
      document.cookie = 'XSRF-TOKEN=fresh-token';
      controller.abort();
    }
    return success();
  });
  await assert.rejects(fetchJson('/clips', { method: 'POST', signal: controller.signal }), {
    kind: 'aborted',
  });
  assert.equal(mock.mock.callCount(), 1);
  const body = new FormData();
  body.append('video', new File(['test'], 'test.mp4'));
  await fetchJson('/clips', {
    method: 'POST',
    body,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  const options = mock.mock.calls.at(-1).arguments[1];
  assert.equal(options.body, body);
  assert.equal(options.headers.has('content-type'), false);
  assert.equal(options.headers.get('x-xsrf-token'), 'fresh-token');
});
