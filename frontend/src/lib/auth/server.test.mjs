import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    const stubs = {
      'server-only': 'export {};',
      'next/headers':
        'export async function cookies() { return { get: (key) => globalThis.testCookies?.[key] }; }',
      'next/navigation':
        'export function redirect(location) { throw Object.assign(new Error("redirect"), { location }); }',
    };
    if (specifier in stubs)
      return {
        url: `data:text/javascript,${encodeURIComponent(stubs[specifier])}`,
        shortCircuit: true,
      };
    return nextResolve(
      specifier.startsWith('@/lib/')
        ? new URL(`../${specifier.slice('@/lib/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { currentMember, requireMember } = await import('./server.ts');
const success = (role) =>
  Response.json({
    isSuccess: true,
    code: 'COMM_200',
    message: 'OK',
    data: { memberId: '1', loginId: 'tester', role },
  });

test('access 만료와 refresh 쿠키가 있으면 서버에서 토큰을 사용하지 않고 브라우저 갱신 화면으로 보낸다', async (context) => {
  globalThis.testCookies = {
    JSESSIONID: { value: 'expired' },
    NPICK_REFRESH: { value: 'a'.repeat(64) },
  };
  const fetch = context.mock.method(globalThis, 'fetch', async (_url, init) => {
    assert.equal(init.headers.get('cookie'), 'JSESSIONID=expired');
    return Response.json(
      { isSuccess: false, code: 'COMM_401', message: 'expired' },
      { status: 401 },
    );
  });
  await assert.rejects(requireMember('/review?view=upload'), {
    location: '/session/renew?returnTo=%2Freview%3Fview%3Dupload',
  });
  assert.equal(fetch.mock.callCount(), 1);
});

test('세션 쿠키가 없으면 요청 없이 로그인으로 복귀 URL을 전달한다', async (context) => {
  globalThis.testCookies = {};
  const mock = context.mock.method(globalThis, 'fetch', async () => {
    throw new Error('unexpected');
  });
  assert.equal(await currentMember(), null);
  await assert.rejects(requireMember('/review?view=upload'), (error) => {
    assert.equal(
      new URL(error.location, 'https://npick.invalid').searchParams.get('returnTo'),
      '/review?view=upload',
    );
    return true;
  });
  assert.equal(mock.mock.callCount(), 0);
});

test('서버는 지정된 내부 주소로 JSESSIONID만 전달하고 세션 응답을 캐싱하지 않는다', async (context) => {
  globalThis.testCookies = {
    JSESSIONID: { value: 'safe-session.1' },
    'XSRF-TOKEN': { value: 'not-forwarded' },
  };
  process.env.API_INTERNAL_BASE_URL = 'http://backend:8080/api/v1';
  context.after(() => {
    delete process.env.API_INTERNAL_BASE_URL;
  });
  const mock = context.mock.method(globalThis, 'fetch', async () => success('EDITOR'));
  assert.equal((await requireMember('/search/results?q=test')).role, 'EDITOR');
  const [url, options] = mock.mock.calls[0].arguments;
  assert.equal(url, 'http://backend:8080/api/v1/auth/me');
  assert.equal(options.headers.get('cookie'), 'JSESSIONID=safe-session.1');
  assert.equal(options.headers.has('x-xsrf-token'), false);
  assert.equal(options.cache, 'no-store');
  assert.equal(options.redirect, 'error');
});

test('위조된 역할 URL과 무관하게 서버 응답이 EDITOR이면 검수 화면을 거부한다', async (context) => {
  globalThis.testCookies = { JSESSIONID: { value: 'editor' } };
  context.mock.method(globalThis, 'fetch', async () => success('EDITOR'));
  await assert.rejects(requireMember('/review?role=reviewer&view=upload'), {
    location: '/search?notice=forbidden',
  });
});

test('REVIEWER는 검수·검색 모두 허용하고 유효하지 않은 쿠키는 전달하지 않는다', async (context) => {
  globalThis.testCookies = { JSESSIONID: { value: 'reviewer' } };
  const mock = context.mock.method(globalThis, 'fetch', async () => success('REVIEWER'));
  assert.equal((await requireMember('/review?view=processing')).role, 'REVIEWER');
  assert.equal((await requireMember('/search')).role, 'REVIEWER');
  globalThis.testCookies = { JSESSIONID: { value: 'bad; injected=value\r\n' } };
  assert.equal(await currentMember(), null);
  assert.equal(mock.mock.callCount(), 2);
});

test('인증 만료만 로그인으로 처리하며 403·500·네트워크 장애는 별도 오류다', async (context) => {
  globalThis.testCookies = { JSESSIONID: { value: 'expired' } };
  const mock = context.mock.method(globalThis, 'fetch', async () =>
    Response.json(
      { isSuccess: false, code: 'COMM_401', message: 'Authentication is required' },
      { status: 401 },
    ),
  );
  assert.equal(await currentMember(), null);
  for (const status of [403, 500]) {
    mock.mock.mockImplementation(async () =>
      Response.json({ isSuccess: false, code: `COMM_${status}`, message: '실패' }, { status }),
    );
    await assert.rejects(currentMember(), { status });
  }
  mock.mock.mockImplementation(async () => {
    throw new TypeError('offline');
  });
  await assert.rejects(currentMember(), { kind: 'network' });
});
