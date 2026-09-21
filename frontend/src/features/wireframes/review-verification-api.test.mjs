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

const { parseVerificationResult, verificationErrorMessages, verifyCorrectionCandidates } =
  await import('./review-verification-api.ts');

// 계약 정본 docs/contracts/web-api.md §6.4 verify 의 응답 예시 그대로.
const contractResponse = {
  execution_id: '9702',
  entered_scenes: [
    {
      scene_id: '9302',
      reason: {
        match: { matched_keywords: ['제주도'], match_evidence: [] },
        score: { base_score: 1.2 },
      },
    },
  ],
  dropped_scenes: [{ scene_id: '9301', reason: 'approved_scene_exclusion' }],
  verification_rule_set: ['8001', '8002'],
};

test('snake_case 응답을 camelCase 로 바꾸고 ID 는 문자열로 보존한다', () => {
  const result = parseVerificationResult(contractResponse);
  assert.deepEqual(result, {
    executionId: '9702',
    enteredScenes: [
      {
        sceneId: '9302',
        reason: {
          match: { matched_keywords: ['제주도'], match_evidence: [] },
          score: { base_score: 1.2 },
        },
      },
    ],
    droppedScenes: [{ sceneId: '9301', reason: 'approved_scene_exclusion' }],
    verificationRuleSet: ['8001', '8002'],
  });
  assert.equal(typeof result.executionId, 'string');
  assert.equal(typeof result.enteredScenes[0].sceneId, 'string');
  assert.equal(typeof result.verificationRuleSet[0], 'string');
});

test('들어온 장면도 빠진 장면도 없는 응답은 정상이다', () => {
  assert.deepEqual(
    parseVerificationResult({
      execution_id: '9703',
      entered_scenes: [],
      dropped_scenes: [],
      verification_rule_set: [],
    }),
    { executionId: '9703', enteredScenes: [], droppedScenes: [], verificationRuleSet: [] },
  );
});

test('계약에 없는 제외 사유와 숫자 ID 는 안전하지 않은 응답으로 거절한다', () => {
  for (const invalid of [
    { ...contractResponse, dropped_scenes: [{ scene_id: '9301', reason: 'unknown_reason' }] },
    { ...contractResponse, execution_id: 9702 },
    { ...contractResponse, verification_rule_set: [8001] },
    { ...contractResponse, entered_scenes: {} },
  ]) {
    assert.throws(() => parseVerificationResult(invalid), ApiClientError);
  }
});

test('세 가지 제외 사유를 모두 읽는다', () => {
  const result = parseVerificationResult({
    ...contractResponse,
    dropped_scenes: [
      { scene_id: '1', reason: 'approved_scene_exclusion' },
      { scene_id: '2', reason: 'false_hit_guard' },
      { scene_id: '3', reason: 'score_drop' },
    ],
  });
  assert.deepEqual(
    result.droppedScenes.map(({ reason }) => reason),
    ['approved_scene_exclusion', 'false_hit_guard', 'score_drop'],
  );
});

test('검수 중 아님(231)과 대기 후보 없음(232)은 서로 다른 문구다', () => {
  assert.notEqual(verificationErrorMessages.SRCH_409_231, verificationErrorMessages.SRCH_409_232);
  for (const code of ['SRCH_403_231', 'SRCH_404_231', 'SRCH_409_231', 'SRCH_409_232']) {
    assert.match(verificationErrorMessages[code], /[가-힣]/);
  }
});

test('본문 없는 POST 를 계약 경로로 보낸다', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    return Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: 'ok',
      data: contractResponse,
    });
  });

  const result = await verifyCorrectionCandidates('41');

  assert.ok(requests[0].input.endsWith('/api/v1/review/inquiries/41/verify'));
  assert.equal(requests[0].init.method, 'POST');
  assert.equal(requests[0].init.body, undefined);
  assert.equal(result.executionId, '9702');
});
