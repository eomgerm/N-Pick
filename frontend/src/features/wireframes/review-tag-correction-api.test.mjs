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

const { createTagCorrectionCandidate, parseTagCorrectionCandidate, tagCorrectionErrorMessage } =
  await import('./review-tag-correction-api.ts');

const replaceOperations = [
  {
    action: 'REJECT',
    scope: 'SCENE',
    tagType: 'location',
    matchValue: '제주시',
    displayName: '제주시',
  },
  {
    action: 'APPROVE',
    scope: 'SCENE',
    tagType: 'location',
    matchValue: '제주도',
    displayName: '제주도',
  },
];

test('생성 응답의 근거 ID는 TSID 문자열 그대로 읽는다', () => {
  assert.deepEqual(
    parseTagCorrectionCandidate({
      feedbackId: '41',
      created: 2,
      evidenceIds: ['499602652184261441', '499602652184261442'],
    }),
    {
      feedbackId: '41',
      created: 2,
      evidenceIds: ['499602652184261441', '499602652184261442'],
    },
  );
});

test('근거 ID가 숫자로 오면 정밀도를 잃으므로 응답을 거부한다', () => {
  assert.throws(
    () =>
      parseTagCorrectionCandidate({
        feedbackId: '41',
        created: 1,
        evidenceIds: [499602652184261441],
      }),
    ApiClientError,
  );
});

test('변경안 개수가 없거나 근거 목록이 배열이 아니면 응답을 거부한다', () => {
  assert.throws(
    () => parseTagCorrectionCandidate({ feedbackId: '41', created: 1, evidenceIds: '9' }),
    ApiClientError,
  );
  assert.throws(
    () => parseTagCorrectionCandidate({ feedbackId: '41', created: null, evidenceIds: [] }),
    ApiClientError,
  );
});

test('교체는 반려·승인 두 변경안을 한 요청으로 보낸다', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    return Response.json({
      isSuccess: true,
      code: 'COMM_201',
      message: 'ok',
      data: { feedbackId: '41', created: 2, evidenceIds: ['9001', '9002'] },
    });
  });

  const created = await createTagCorrectionCandidate('41', replaceOperations);

  assert.deepEqual(created.evidenceIds, ['9001', '9002']);
  assert.ok(
    requests[0].input.endsWith('/api/v1/review/inquiries/41/tag-correction-candidate'),
    requests[0].input,
  );
  assert.equal(requests[0].init.method, 'POST');
  assert.deepEqual(JSON.parse(requests[0].init.body), { operations: replaceOperations });
  // 이 엔드포인트에는 멱등 키가 없다. 보내면 서버 계약에 없는 헤더가 된다.
  assert.equal(new Headers(requests[0].init.headers).get('Idempotency-Key'), null);
});

test('문의 ID가 경로 형식이 아니면 요청을 보내지 않는다', async (context) => {
  const fetchMock = context.mock.method(globalThis, 'fetch', async () => {
    throw new Error('요청을 보내면 안 된다');
  });
  await assert.rejects(
    () => createTagCorrectionCandidate('41/../42', replaceOperations),
    ApiClientError,
  );
  assert.equal(fetchMock.mock.callCount(), 0);
});

test('계약 오류 코드를 검수자용 한국어 안내로 바꾼다', () => {
  const codes = [
    'TAG_400_001',
    'TAG_400_002',
    'TAG_400_003',
    'TAG_403_001',
    'TAG_403_002',
    'TAG_404_001',
    'TAG_409_001',
    'TAG_409_002',
  ];
  const messages = codes.map((code) =>
    tagCorrectionErrorMessage(new ApiClientError('api', 400, { code })),
  );
  assert.ok(messages.every((message) => typeof message === 'string' && message.length > 0));
  assert.equal(new Set(messages).size, codes.length);
  assert.equal(
    tagCorrectionErrorMessage(new ApiClientError('api', 500, { code: 'COMM_500' })),
    null,
  );
  assert.equal(tagCorrectionErrorMessage(new Error('network down')), null);
});
