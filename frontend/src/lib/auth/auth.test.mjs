import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { QueryClient } from '@tanstack/react-query';

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

const { ApiClientError } = await import('../api/error.ts');
const {
  parseMember,
  safeReturnTo,
  postLoginPath,
  canAccessPath,
  isSessionExpired,
  loginPath,
  pageLocation,
} = await import('./member.ts');
const { clearMemberDrafts, discardQueries } = await import('./browser.ts');
const { otherTabSessionEvent, sessionMessageSource } = await import('./browser.ts');
const { login, getMember, logout, memberQueryOptions } = await import('./api.ts');
const editor = { memberId: '1', loginId: 'editor', role: 'EDITOR' };
const success = (data) => Response.json({ isSuccess: true, code: 'COMM_200', message: 'OK', data });
const failure = (status, code) =>
  Response.json({ isSuccess: false, code, message: '요청 실패' }, { status });

test('서버 계정 DTO는 알려진 두 역할만 허용하고 불필요한 필드는 버린다', () => {
  assert.deepEqual(parseMember({ ...editor, password: 'not-retained', session: 'secret' }), editor);
  assert.equal(
    parseMember({ ...editor, memberId: '9223372036854775807' }).memberId,
    '9223372036854775807',
  );
  for (const data of [
    null,
    {},
    { ...editor, role: 'ADMIN' },
    { ...editor, role: 'editor' },
    { ...editor, loginId: '' },
    { ...editor, memberId: 1 },
    { ...editor, memberId: '0' },
    { ...editor, memberId: '-1' },
    { ...editor, memberId: '01' },
    { ...editor, memberId: '1.0' },
    { ...editor, memberId: '9223372036854775808' },
    { ...editor, memberId: Number.MAX_SAFE_INTEGER + 1 },
  ]) {
    assert.throws(() => parseMember(data), ApiClientError);
  }
});

test('검색은 두 역할에 허용하고 검수 화면은 검수자만 허용한다', () => {
  for (const role of ['EDITOR', 'REVIEWER']) {
    assert.equal(canAccessPath(role, '/search'), true);
    assert.equal(canAccessPath(role, '/search/results'), true);
  }
  assert.equal(canAccessPath('EDITOR', '/review'), false);
  assert.equal(canAccessPath('REVIEWER', '/review'), true);
  assert.equal(canAccessPath('REVIEWER', '/review-unknown'), false);
  assert.equal(postLoginPath('EDITOR', '/review?view=upload'), '/search');
  assert.equal(postLoginPath('REVIEWER'), '/review');
  assert.equal(postLoginPath('REVIEWER', '/search/results?q=news'), '/search/results?q=news');
});

test('복귀 URL은 로컬 보호 화면만 허용하여 우회 주소와 외부 이동을 차단한다', () => {
  for (const value of [
    'https://evil.example',
    '//evil.example',
    '/\\evil.example',
    '/login',
    '/api/v1/auth/logout',
    '/landing',
    '/review#x',
    '/%72eview',
    '/review\n',
    ['review'],
    '/search/../login',
    '/review?x=1\r\n',
    '/search?x=' + 'a'.repeat(8192),
  ]) {
    assert.equal(safeReturnTo(value), undefined, String(value));
  }
  const location = pageLocation('/review', { view: 'processing', tab: 'uploads', clip: '123' });
  assert.equal(postLoginPath('REVIEWER', location), location);
  assert.equal(
    new URL(loginPath(location, 'expired'), 'https://npick.invalid').searchParams.get('returnTo'),
    location,
  );

  assert.equal(loginPath('https://evil.example'), '/login');
});

test('세션 변경 알림은 자기 탭을 다시 로드하지 않고 다른 탭만 갱신한다', () => {
  assert.equal(otherTabSessionEvent({ event: 'login', source: sessionMessageSource }), undefined);
  assert.equal(otherTabSessionEvent({ event: 'logout', source: sessionMessageSource }), undefined);
  assert.equal(otherTabSessionEvent({ event: 'login', source: 'another-tab' }), 'login');
  assert.equal(otherTabSessionEvent({ event: 'logout', source: 'another-tab' }), 'logout');
  for (const value of [
    null,
    'login',
    {},
    { event: 'logout' },
    { event: 'unknown', source: 'other' },
  ]) {
    assert.equal(otherTabSessionEvent(value), undefined);
  }
});

test('인증 만료와 자격 증명 실패·권한/CSRF 실패·네트워크 실패는 구분한다', () => {
  assert.equal(isSessionExpired(new ApiClientError('http', 401, { code: 'COMM_401' })), true);
  for (const error of [
    new ApiClientError('http', 401, { code: 'MEMBER_401_001' }),
    new ApiClientError('http', 403, { code: 'COMM_403' }),
    new ApiClientError('network', 0),
  ]) {
    assert.equal(isSessionExpired(error), false);
  }
});

test('계정 변경은 다른 계정 초안만, 로그아웃은 앱 소유 초안만 지운다', () => {
  const entries = new Map([
    ['npick:1:search', 'my draft'],
    ['npick:2:inquiry', 'other draft'],
    ['npick:11:search', 'eleven'],
    ['external:key', 'keep'],
    ['npick:settings', 'keep'],
  ]);
  const storage = {
    get length() {
      return entries.size;
    },
    key: (i) => [...entries.keys()][i],
    removeItem: (key) => entries.delete(key),
  };
  clearMemberDrafts(storage, '1');
  assert.deepEqual([...entries.keys()], ['npick:1:search', 'external:key', 'npick:settings']);
  clearMemberDrafts(storage);
  assert.deepEqual([...entries.keys()], ['external:key', 'npick:settings']);
});

test('로그인 요청은 역할을 보내지 않고 세션 쿠키 유지까지 확인한다', async (context) => {
  const mock = context.mock.method(globalThis, 'fetch', async () => success(editor));
  assert.deepEqual(await login({ loginId: 'editor', password: 'test-password' }), editor);
  assert.equal(mock.mock.callCount(), 2);
  const [[loginUrl, options], [meUrl]] = mock.mock.calls.map((call) => call.arguments);
  assert.ok(loginUrl.endsWith('/auth/login'));
  assert.deepEqual(JSON.parse(options.body), { loginId: 'editor', password: 'test-password' });
  assert.equal(options.credentials, 'include');
  assert.equal(options.redirect, 'error');
  assert.ok(meUrl.endsWith('/auth/me'));
});

test('잘못된 비밀번호는 폼 오류이며 요청을 자동 반복하지 않는다', async (context) => {
  const mock = context.mock.method(globalThis, 'fetch', async () => failure(401, 'MEMBER_401_001'));
  await assert.rejects(login({ loginId: 'editor', password: 'wrong' }), { code: 'MEMBER_401_001' });
  assert.equal(mock.mock.callCount(), 1);
});

test('로그인 성공 응답 뒤 쿠키가 유지되지 않으면 성공 이동으로 처리하지 않는다', async (context) => {
  context.mock.method(globalThis, 'fetch', async (url) =>
    url.endsWith('/auth/login') ? success(editor) : failure(401, 'COMM_401'),
  );
  await assert.rejects(login({ loginId: 'editor', password: 'test' }), {
    code: 'CLIENT_SESSION_COOKIE_UNAVAILABLE',
  });
});

test('me 조회 취소를 전달하고 로그아웃 실패를 성공으로 바꾸지 않는다', async (context) => {
  const controller = new AbortController();
  const mock = context.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.ok(options.signal instanceof AbortSignal);
    return failure(403, 'COMM_403');
  });
  await assert.rejects(getMember(controller.signal), { code: 'COMM_403' });
  await assert.rejects(logout(), { code: 'COMM_403' });
  assert.equal(mock.mock.callCount(), 2);
  assert.equal(memberQueryOptions.retry, false);
  assert.deepEqual(memberQueryOptions.queryKey, ['auth', 'me']);
});

test('세션 정리는 진행 중 query를 취소하고 사용자 cache와 mutation을 폐기한다', async () => {
  const client = new QueryClient();
  client.setQueryData(['auth', 'me'], editor);
  client.setQueryData(['search'], ['private']);
  let aborted = false;
  const pending = client
    .fetchQuery({
      queryKey: ['pending'],
      queryFn: ({ signal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => {
            aborted = true;
            reject(new Error('cancelled'));
          });
        }),
    })
    .catch(() => undefined);
  await discardQueries(client);
  await pending;
  assert.equal(aborted, true);
  assert.equal(client.getQueryCache().getAll().length, 0);
  assert.equal(client.getMutationCache().getAll().length, 0);
});
