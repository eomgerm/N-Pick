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
  createTagCorrectionCandidate,
  getReviewInquiries,
  getReviewInquiry,
  parseCommaSeparatedTags,
  parseReviewInquiryDetail,
  parseReviewInquiryList,
  parseTagCorrectionCandidate,
  resolveReviewInquiry,
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

test('영상 제목이 없으면 null을 보존하고 유효한 장면 구간은 그대로 읽는다', () => {
  const parsed = parseReviewInquiryList({
    items: [{ ...item, scene: { ...scene, clipTitle: null } }],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
    statusCounts: { open: 1, reviewing: 0, closed: 0 },
  });
  assert.equal(parsed.items[0].scene.clipTitle, null);
  assert.equal(parsed.items[0].scene.startTimeMs, 42000);
  assert.equal(parsed.items[0].scene.endTimeMs, 49000);
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
        tagType: 'location',
        matchValue: '서울역',
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
  assert.equal(detail.evidence[0].tagType, 'location');
  assert.equal(detail.evidence[0].matchValue, '서울역');
});

test('쉼표 입력은 공백과 빈 값을 제거하고 중복 없이 여러 태그로 나눈다', () => {
  assert.deepEqual(parseCommaSeparatedTags(' 서울, 부산 ,,서울, 광주 '), ['서울', '부산', '광주']);
  assert.deepEqual(parseCommaSeparatedTags(''), []);
});

test('태그 교정 후보 응답은 생성 수와 근거 ID 개수가 같아야 한다', () => {
  assert.deepEqual(
    parseTagCorrectionCandidate({
      feedbackId: '41',
      created: 2,
      evidenceIds: ['61', '62'],
    }),
    { feedbackId: '41', created: 2, evidenceIds: ['61', '62'] },
  );
  assert.throws(
    () =>
      parseTagCorrectionCandidate({
        feedbackId: '41',
        created: 1,
        evidenceIds: ['61', '62'],
      }),
    ApiClientError,
  );
});

test('누락·불일치 ID와 모르는 상태는 안전하지 않은 응답으로 거절한다', () => {
  for (const invalid of [
    { ...item, feedbackId: 41 },
    { ...item, status: 'PENDING' },
    { ...item, sceneId: '99' },
    { ...item, createdAt: '잘못된 시각' },
    { ...item, scene: { ...scene, endTimeMs: scene.startTimeMs } },
    { ...item, scene: { ...scene, processingNo: 0 } },
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

test('검색 결과 순위는 1 이상이어야 한다', () => {
  assert.throws(
    () =>
      parseReviewInquiryDetail({
        ...item,
        comment: null,
        resultRank: 0,
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
      }),
    ApiClientError,
  );
});

test('문의·태그 교정 API 경로와 요청 본문을 계약대로 보낸다', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    const data =
      String(input).endsWith('/claim') || String(input).endsWith('/resolution')
        ? undefined
        : String(input).endsWith('/tag-correction-candidate')
          ? { feedbackId: '41', created: 2, evidenceIds: ['61', '62'] }
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
  await resolveReviewInquiry('41', 'no_action', '문제 없음');
  await createTagCorrectionCandidate('41', [
    {
      action: 'APPROVE',
      scope: 'SCENE',
      tagType: 'location',
      matchValue: '서울',
      displayName: '서울',
    },
    {
      action: 'REJECT',
      scope: 'CLIP',
      tagType: 'keyword',
      matchValue: '교통',
      displayName: '교통',
    },
  ]);

  const listUrl = new URL(requests[0].input);
  assert.equal(listUrl.pathname, '/api/v1/review/inquiries');
  assert.equal(listUrl.searchParams.get('page'), '1');
  assert.equal(listUrl.searchParams.get('size'), '10');
  assert.equal(listUrl.searchParams.get('status'), 'OPEN');
  assert.ok(requests[1].input.endsWith('/api/v1/review/inquiries/41'));
  assert.ok(requests[2].input.endsWith('/api/v1/review/inquiries/41/claim'));
  assert.equal(requests[2].init.method, 'POST');
  assert.equal(new Headers(requests[2].init.headers).get('Idempotency-Key'), 'claim-key');
  assert.ok(requests[3].input.endsWith('/api/v1/review/inquiries/41/resolution'));
  assert.equal(requests[3].init.method, 'PUT');
  assert.deepEqual(JSON.parse(requests[3].init.body), {
    resolution: 'no_action',
    note: '문제 없음',
  });
  assert.ok(requests[4].input.endsWith('/api/v1/review/inquiries/41/tag-correction-candidate'));
  assert.equal(requests[4].init.method, 'POST');
  assert.deepEqual(JSON.parse(requests[4].init.body), {
    operations: [
      {
        action: 'APPROVE',
        scope: 'SCENE',
        tagType: 'location',
        matchValue: '서울',
        displayName: '서울',
      },
      {
        action: 'REJECT',
        scope: 'CLIP',
        tagType: 'keyword',
        matchValue: '교통',
        displayName: '교통',
      },
    ],
  });
});
