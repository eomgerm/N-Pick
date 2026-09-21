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

const { createSceneExcludeCandidate, getSceneExcludeMessage } =
  await import('./review-scene-exclude-api.ts');

const candidate = { searchRuleId: '9812345678901234567', feedbackId: '41', active: false };

function stubFetch(context, body, status = 200) {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    return new Response(body, { status, headers: { 'content-type': 'application/json' } });
  });
  return requests;
}

function envelope(data) {
  return `{"isSuccess":true,"code":"COMM_200","message":"ok","data":${data}}`;
}

test('신규 생성(201)은 계약대로 요청하고 TSID를 문자열로 보존한다', async (context) => {
  const requests = stubFetch(
    context,
    envelope('{"searchRuleId":9812345678901234567,"feedbackId":41,"active":false}'),
    201,
  );

  assert.deepEqual(await createSceneExcludeCandidate('41', '31', 'exclude-key'), candidate);

  assert.ok(
    requests[0].input.endsWith('/api/v1/review/inquiries/41/scene-exclude-candidate'),
    requests[0].input,
  );
  assert.equal(requests[0].init.method, 'POST');
  assert.equal(new Headers(requests[0].init.headers).get('Idempotency-Key'), 'exclude-key');
  assert.deepEqual(JSON.parse(requests[0].init.body), { targetSceneId: '31' });
});

test('멱등 재생(200)도 성공으로 읽는다', async (context) => {
  stubFetch(context, envelope(JSON.stringify({ ...candidate, active: true })), 200);

  assert.deepEqual(await createSceneExcludeCandidate('41', '31', 'exclude-key'), {
    ...candidate,
    active: true,
  });
});

test('응답 모양이 계약과 다르면 거부한다', async (context) => {
  stubFetch(context, envelope('{"searchRuleId":"","feedbackId":"41","active":false}'));
  await assert.rejects(() => createSceneExcludeCandidate('41', '31', 'key'), ApiClientError);
});

test('오류 코드 211과 212는 서로 다른 한국어 문구로 구분한다', () => {
  const messages = [
    'SRCH_400_211',
    'SRCH_400_212',
    'SRCH_403_211',
    'SRCH_403_212',
    'SRCH_404_211',
    'SRCH_409_211',
    'SRCH_409_212',
  ].map((code) => getSceneExcludeMessage(new ApiClientError('api', 400, { code })));

  assert.equal(new Set(messages).size, messages.length);
  for (const message of messages) assert.match(message, /[가-힣]/);
});

test('모르는 오류에는 기본 안내 문구를 준다', () => {
  assert.match(getSceneExcludeMessage(new ApiClientError('network', 0)), /[가-힣]/);
});
