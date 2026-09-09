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

const {
  claimReviewInquiry,
  getReviewInquiries,
  getReviewInquiry,
  parseReviewInquiryDetail,
  parseReviewInquiryList,
} = await import('./review-inquiry-api.ts');

const scene = {
  sceneId: '31',
  clipId: '21',
  clipTitle: '저녁 뉴스',
  startTimeMs: 42000,
  endTimeMs: 49000,
  pipelineRunId: '11',
  processingNo: 1,
};

const item = {
  feedbackId: '41',
  status: 'OPEN',
  resolution: null,
  createdAt: '2026-09-09T01:00:00Z',
  queryText: '귀성길 정체',
  sceneId: '31',
  scene,
  hasComment: true,
};

test('목록 응답은 백엔드 대문자 상태를 프론트 상태로 정규화한다', () => {
  assert.deepEqual(
    parseReviewInquiryList({
      items: [item],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      statusCounts: { open: 1, reviewing: 0, closed: 0 },
    }),
    {
      items: [{ ...item, status: 'open' }],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      statusCounts: { open: 1, reviewing: 0, closed: 0 },
    },
  );
});

test('상세 응답은 당시 실행·근거·담당 이력을 보존한다', () => {
  const detail = parseReviewInquiryDetail({
    ...item,
    comment: '다른 장면 같습니다.',
    resultRank: 2,
    resultExplainJson: '{"score":0.8}',
    execution: {
      queryText: item.queryText,
      explicitFiltersJson: '{}',
      parsedQueryJson: '{}',
      resolverOutputJson: '{}',
      appliedRulesJson: '[]',
      appliedExcludesJson: '[]',
    },
    evidence: [
      {
        taggingId: '51',
        tagName: '서울역',
        source: 'ocr',
        verifiedState: 'VERIFIED',
        scope: 'SCENE',
      },
    ],
    history: {
      reviewedById: null,
      reviewerName: null,
      reviewerLoginId: null,
      reviewStartedAt: null,
      verifiedByExecutionId: null,
    },
    resolutionNote: null,
  });
  assert.equal(detail.status, 'open');
  assert.equal(detail.comment, '다른 장면 같습니다.');
  assert.equal(detail.evidence[0].taggingId, '51');
});

test('누락·불일치 ID와 모르는 상태는 안전하지 않은 응답으로 거절한다', () => {
  for (const invalid of [
    { ...item, feedbackId: 41 },
    { ...item, status: 'PENDING' },
    { ...item, sceneId: '99' },
  ]) {
    assert.throws(
      () =>
        parseReviewInquiryList({
          items: [invalid],
          page: 0,
          size: 10,
          totalElements: 1,
          totalPages: 1,
          statusCounts: { open: 1, reviewing: 0, closed: 0 },
        }),
      ApiClientError,
    );
  }
});

test('목록·상세·선점 API 경로와 query, 멱등성 키를 계약대로 보낸다', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    const data = String(input).endsWith('/claim')
      ? undefined
      : String(input).includes('/review/inquiries/41')
        ? {
            ...item,
            comment: null,
            resultRank: 1,
            resultExplainJson: null,
            execution: {
              queryText: item.queryText,
              explicitFiltersJson: null,
              parsedQueryJson: null,
              resolverOutputJson: null,
              appliedRulesJson: null,
              appliedExcludesJson: null,
            },
            evidence: [],
            history: {
              reviewedById: null,
              reviewerName: null,
              reviewerLoginId: null,
              reviewStartedAt: null,
              verifiedByExecutionId: null,
            },
            resolutionNote: null,
          }
        : {
            items: [item],
            page: 1,
            size: 10,
            totalElements: 1,
            totalPages: 1,
            statusCounts: { open: 1, reviewing: 0, closed: 0 },
          };
    return Response.json({ isSuccess: true, code: 'COMM_200', message: 'ok', data });
  });

  await getReviewInquiries(1, 'open');
  await getReviewInquiry('41');
  await claimReviewInquiry('41', 'claim-key');

  const listUrl = new URL(requests[0].input);
  assert.equal(listUrl.pathname, '/api/v1/review/inquiries');
  assert.equal(listUrl.searchParams.get('page'), '1');
  assert.equal(listUrl.searchParams.get('size'), '10');
  assert.equal(listUrl.searchParams.get('status'), 'OPEN');
  assert.ok(requests[1].input.endsWith('/api/v1/review/inquiries/41'));
  assert.ok(requests[2].input.endsWith('/api/v1/review/inquiries/41/claim'));
  assert.equal(requests[2].init.method, 'POST');
  assert.equal(new Headers(requests[2].init.headers).get('Idempotency-Key'), 'claim-key');
});
