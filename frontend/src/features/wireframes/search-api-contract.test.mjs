import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { ApiClientError } from '../../lib/api/error.ts';

const sourceRoot = new URL('../../', import.meta.url);

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      const moduleUrl = new URL(specifier.slice(2), sourceRoot);
      for (const extension of ['.ts', '.tsx']) {
        const candidate = new URL(`${moduleUrl.href}${extension}`);
        if (existsSync(fileURLToPath(candidate))) {
          return { url: candidate.href, shortCircuit: true };
        }
      }
    }
    return nextResolve(specifier, context);
  },
});

const { createSearchRequestBody, parseSearchResponse } = await import('./search-api-contract.ts');
const { presentSearchResponse, searchScenes } = await import('./search-results-api.ts');

const emptyRange = { from: '', to: '' };

test('제목 없는 검색 결과는 null을 보존하고 화면에서만 대체 제목을 표시한다', () => {
  for (const description of ['서울역 귀성 인파', null]) {
    const raw = createResponse();
    raw.results[0] = createScene(1, { display_name: null, scene_description: description });

    const parsed = parseSearchResponse(raw);
    const view = presentSearchResponse(parsed);

    assert.equal(view.results.length, 10);
    assert.equal(view.results[0].displayName, '제목 없는 영상');
    assert.equal(view.results[0].clip, '제목 없는 영상');
    assert.equal(view.results[0].title, description ?? '제목 없는 영상');
    assert.equal(view.results[1].displayName, '저녁 뉴스 2');
    assert.equal(view.results[1].title, '서울역 장면 2');
    assert.equal(parsed.results[0].displayName, null);
  }
});

test('영상 제목은 null만 추가 허용하고 누락·빈 문자열·잘못된 타입은 거절한다', () => {
  for (const invalidTitle of [undefined, '', '   ', 123]) {
    const raw = createResponse();
    raw.results[0] = createScene(1, { display_name: invalidTitle });
    assert.throws(() => parseSearchResponse(raw), ApiClientError);
  }
});

test('실제 검색 adapter는 clip ID와 소수 초, 추가 근거와 degraded 상태를 보존한다', () => {
  const raw = createResponse({ status: 'degraded', degraded_reasons: ['dense_unavailable'] });
  raw.results[0] = createScene(1, {
    clip_id: '9007199254740993',
    start_time_ms: 1250,
    end_time_ms: 2700,
  });
  raw.results[0].match_evidence.push({
    field: 'tag',
    value: '귀성',
    source: 'reviewer',
    verification_status: 'verified',
  });
  const view = presentSearchResponse(parseSearchResponse(raw));
  assert.equal(view.results[0].clipId, '9007199254740993');
  assert.equal(view.results[0].sceneStart, 1.25);
  assert.equal(view.results[0].sceneEnd, 2.7);
  assert.equal(view.results[0].additionalEvidence[0].field, '태그');
  assert.deepEqual(view.execution.degradedReasons, ['dense-unavailable']);
  assert.deepEqual(view.details, {
    resolverStatus: 'succeeded',
    excludedCount: 0,
    exclusionReasons: [],
  });
});

test('근거 출처는 한국어 라벨로 바꾸고 어휘 밖 값은 원값을 노출하지 않는다', () => {
  const raw = createResponse();
  raw.results[0] = createScene(1, {
    match_evidence: [
      { field: 'ocr', value: '서울역', source: 'keyframe_ocr', verification_status: 'verified' },
      { field: 'caption', value: '귀성 인파', source: 'vlm', verification_status: 'unverified' },
      { field: 'tag', value: '귀성', source: 'reviewer_feedback', verification_status: 'verified' },
      {
        field: 'tag',
        value: '설 연휴',
        source: 'legacy_source',
        verification_status: 'unverified',
      },
    ],
  });

  const view = presentSearchResponse(parseSearchResponse(raw));
  const [scene] = view.results;

  assert.equal(scene.matchEvidence.source, '대표 이미지 글자 인식');
  assert.equal(scene.source, '대표 이미지 글자 인식');
  assert.deepEqual(
    scene.additionalEvidence.map((evidence) => evidence.source),
    ['AI 화면 분석', '아카이빙 팀 피드백', '정보 없음'],
  );
});

test('검색 API는 요청 객체를 한 번만 JSON 직렬화한다', async () => {
  const originalFetch = globalThis.fetch;
  const body = { query: '뉴스', explicit_filters: {} };
  globalThis.fetch = async (url, init) => {
    assert.ok(String(url).endsWith('/api/v1/search'));
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), body);
    return new Response(
      JSON.stringify({
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: createResponse(),
      }),
      { status: 200 },
    );
  };
  try {
    assert.equal((await searchScenes(body)).results.length, 10);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

function createScene(rank, overrides = {}) {
  return {
    search_result_id: String(1000 + rank),
    scene_id: String(2000 + rank),
    clip_id: String(3000 + rank),
    rank,
    display_name: `저녁 뉴스 ${rank}`,
    scene_description: `서울역 장면 ${rank}`,
    start_time_ms: rank * 1000,
    end_time_ms: rank * 1000 + 7000,
    broadcast_date: { value: '2026-09-10', verification_status: 'verified' },
    filmed_date: { value: null, verification_status: 'unknown' },
    shot_type: 'b_roll',
    scene_type: '역사 인파',
    matched_keywords: ['서울역', '귀성객'],
    match_evidence: [
      {
        field: 'ocr',
        value: '서울역 귀성객',
        source: 'keyframe_ocr',
        verification_status: 'verified',
      },
    ],
    ...overrides,
  };
}

function createResponse(overrides = {}) {
  return {
    search_execution_id: '398021847361024',
    status: 'succeeded',
    degraded_reasons: [],
    query_resolution_status: 'resolved',
    has_applied_review_rule: false,
    guard_summary: { excluded_result_count: 0, reasons: [] },
    shortage_reasons: [],
    results: Array.from({ length: 10 }, (_, index) => createScene(index + 1)),
    ...overrides,
  };
}

test('검색 요청은 trim한 질의와 빈 명시 필터 객체를 분리한다', () => {
  assert.deepEqual(
    createSearchRequestBody({ query: '  명절 교통  ', broadcast: emptyRange, filming: emptyRange }),
    { query: '명절 교통', explicit_filters: {} },
  );
});

test('방송일과 촬영일은 inclusive 범위를 별도 필드로 전송한다', () => {
  assert.deepEqual(
    createSearchRequestBody({
      query: '귀성길',
      broadcast: { from: '2026-09-01', to: '2026-09-03' },
      filming: { from: '2026-08-28', to: '2026-08-29' },
    }),
    {
      query: '귀성길',
      explicit_filters: {
        broadcast_date: { from: '2026-09-01', to: '2026-09-03' },
        filmed_date: { from: '2026-08-28', to: '2026-08-29' },
      },
    },
  );
});

test('빈 질의나 불완전하고 잘못된 날짜는 요청 본문을 만들지 않는다', () => {
  for (const input of [
    { query: '   ', broadcast: emptyRange, filming: emptyRange },
    {
      query: '귀성길',
      broadcast: { from: '2026-09-01', to: '' },
      filming: emptyRange,
    },
    {
      query: '귀성길',
      broadcast: emptyRange,
      filming: { from: '2026-02-30', to: '2026-03-01' },
    },
  ]) {
    assert.equal(createSearchRequestBody(input), null);
  }
});

test('정상 응답은 서버가 준 Top 10 순서를 그대로 보존하고 문자열 ID를 유지한다', () => {
  const parsed = parseSearchResponse(createResponse());

  assert.equal(parsed.searchExecutionId, '398021847361024');
  assert.deepEqual(
    parsed.results.map(({ rank, sceneId }) => [rank, sceneId]),
    Array.from({ length: 10 }, (_, index) => [index + 1, String(2001 + index)]),
  );
  assert.deepEqual(parsed.results[0].broadcastDate, {
    value: '2026-09-10',
    verificationStatus: 'verified',
  });
  assert.equal(parsed.results[0].matchEvidence[0].verificationStatus, 'verified');
});

test('snapshot 저장 실패는 저장 ID 없는 결과와 문의 불가 상태를 명시한다', () => {
  const parsed = parseSearchResponse(
    createResponse({
      search_execution_id: null,
      status: 'degraded',
      degraded_reasons: ['snapshot_save_failed'],
      shortage_reasons: ['candidate_pool_exhausted'],
      results: [createScene(1, { search_result_id: null })],
    }),
  );

  assert.equal(parsed.searchExecutionId, null);
  assert.equal(parsed.results[0].searchResultId, null);
  assert.deepEqual(parsed.degradedReasons, ['snapshot_save_failed']);
});

test('fallback과 guard·부족 사유를 분리해 보존한다', () => {
  const parsed = parseSearchResponse(
    createResponse({
      status: 'degraded',
      degraded_reasons: ['resolver_fallback'],
      query_resolution_status: 'fallback',
      has_applied_review_rule: true,
      guard_summary: {
        excluded_result_count: 2,
        reasons: ['explicit_date_conflict', 'approved_scene_exclusion'],
      },
      shortage_reasons: ['guard_excluded'],
      results: Array.from({ length: 8 }, (_, index) => createScene(index + 1)),
    }),
  );

  assert.equal(parsed.queryResolutionStatus, 'fallback');
  assert.equal(parsed.hasAppliedReviewRule, true);
  assert.equal(parsed.guardSummary.excludedResultCount, 2);
  assert.deepEqual(parsed.shortageReasons, ['guard_excluded']);

  assert.deepEqual(presentSearchResponse(parsed).details, {
    resolverStatus: 'fallback',
    excludedCount: 2,
    exclusionReasons: ['명시한 날짜와 검증된 날짜가 일치하지 않음', '승인된 장면 제외 규칙에 해당'],
  });
});

test('결과 0건은 빈 배열과 후보 부족 사유로 정상 응답한다', () => {
  const parsed = parseSearchResponse(
    createResponse({ results: [], shortage_reasons: ['candidate_pool_exhausted'] }),
  );

  assert.deepEqual(parsed.results, []);
  assert.deepEqual(parsed.shortageReasons, ['candidate_pool_exhausted']);
});

test('정밀도를 잃는 ID와 서버 순서를 다시 계산해야 하는 응답은 거절한다', () => {
  for (const response of [
    createResponse({ search_execution_id: 398021847361024 }),
    createResponse({
      results: [createScene(2)],
      shortage_reasons: ['candidate_pool_exhausted'],
    }),
    createResponse({
      results: Array.from({ length: 11 }, (_, index) => createScene(index + 1)),
    }),
  ]) {
    assert.throws(() => parseSearchResponse(response), ApiClientError);
  }
});

test('서로 모순되는 상태·날짜·guard 계약은 거절한다', () => {
  for (const response of [
    createResponse({ status: 'succeeded', degraded_reasons: ['dense_unavailable'] }),
    createResponse({ query_resolution_status: 'fallback' }),
    createResponse({
      search_execution_id: '1',
      status: 'degraded',
      degraded_reasons: ['snapshot_save_failed'],
      results: Array.from({ length: 10 }, (_, index) =>
        createScene(index + 1, { search_result_id: null }),
      ),
    }),
    createResponse({
      guard_summary: { excluded_result_count: 1, reasons: [] },
    }),
    createResponse({
      results: [createScene(1)],
      shortage_reasons: [],
    }),
    createResponse({
      results: [
        createScene(1, {
          broadcast_date: { value: null, verification_status: 'verified' },
        }),
        ...Array.from({ length: 9 }, (_, index) => createScene(index + 2)),
      ],
    }),
    createResponse({
      results: [
        createScene(1, { match_evidence: [] }),
        ...Array.from({ length: 9 }, (_, index) => createScene(index + 2)),
      ],
    }),
  ]) {
    assert.throws(() => parseSearchResponse(response), ApiClientError);
  }
});
