import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

import { ApiClientError } from '../../lib/api/error.ts';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice('@/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { confirmCorrection, confirmErrorMessages } = await import('./review-confirm-api.ts');

function stubFetch(context, respond) {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    return respond();
  });
  return requests;
}

// 계약 정본 docs/contracts/web-api.md §6.4 confirm: 본문 { executionId }, 성공은 body 없는 200.
const bodylessSuccess = () =>
  Response.json({ isSuccess: true, code: 'COMM_200', message: 'ok' }, { status: 200 });

test('계약 경로로 executionId 본문만 POST 한다', async (context) => {
  const requests = stubFetch(context, bodylessSuccess);

  await confirmCorrection('41', '9702');

  assert.ok(requests[0].input.endsWith('/api/v1/review/inquiries/41/confirm'));
  assert.equal(requests[0].init.method, 'POST');
  assert.deepEqual(JSON.parse(requests[0].init.body), { executionId: '9702' });
});

test('본문 없는 200 은 성공이다', async (context) => {
  stubFetch(context, bodylessSuccess);
  assert.equal(await confirmCorrection('41', '9702'), undefined);
});

test('같은 검증 실행의 재요청도 성공으로 돌아온다(멱등)', async (context) => {
  stubFetch(context, bodylessSuccess);
  await confirmCorrection('41', '9702');
  await assert.doesNotReject(confirmCorrection('41', '9702'));
});

test('서버 오류 코드는 ApiClientError 로 올라온다', async (context) => {
  stubFetch(context, () =>
    Response.json({ isSuccess: false, code: 'CONFIRM_409_003', message: 'drift' }, { status: 409 }),
  );

  await assert.rejects(confirmCorrection('41', '9702'), (error) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.code, 'CONFIRM_409_003');
    return true;
  });
});

test('경로에 쓸 수 없는 ID 는 요청 전에 거절한다', async (context) => {
  const requests = stubFetch(context, bodylessSuccess);

  for (const [feedbackId, executionId] of [
    ['../41', '9702'],
    ['41', '9702 '],
    ['0', '9702'],
    ['41', ''],
  ]) {
    await assert.rejects(confirmCorrection(feedbackId, executionId), ApiClientError);
  }
  assert.equal(requests.length, 0);
});

test('계약의 오류 코드 8종 모두 한국어 안내가 있다', () => {
  for (const code of [
    'CONFIRM_403_001',
    'CONFIRM_403_002',
    'CONFIRM_404_001',
    'CONFIRM_404_002',
    'CONFIRM_409_001',
    'CONFIRM_409_002',
    'CONFIRM_409_003',
    'CONFIRM_409_004',
  ]) {
    assert.match(confirmErrorMessages[code], /[가-힣]/);
  }
});

test('상태 변경(003)과 장면 소실(004)은 다른 문구이되 둘 다 재검증을 안내한다', () => {
  assert.notEqual(confirmErrorMessages.CONFIRM_409_003, confirmErrorMessages.CONFIRM_409_004);
  assert.match(confirmErrorMessages.CONFIRM_409_003, /다시 검증해 주세요/);
  assert.match(confirmErrorMessages.CONFIRM_409_004, /다시 검증해 주세요/);
});

test('검수자 아님(403_001)과 담당자 아님(403_002)은 서로 다른 문구다', () => {
  assert.notEqual(confirmErrorMessages.CONFIRM_403_001, confirmErrorMessages.CONFIRM_403_002);
});
