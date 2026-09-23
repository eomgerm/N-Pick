import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

import { ApiClientError } from '../../lib/api/error.ts';
import { searchFixture } from '../../../e2e/search-fixture.ts';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice(2)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const {
  parseMySearchHistoryPage,
  parseMySearchHistoryDetail,
  getMySearchHistory,
  getMySearchHistoryDetail,
  clearMySearchHistory,
  mySearchHistoryKeys,
} = await import('./my-search-history-api.ts');
const { parseSearchResponse } = await import('./search-api-contract.ts');
const { presentSearchResponse } = await import('./search-results-api.ts');

function detailFixture(overrides = {}) {
  const snapshot = structuredClone(searchFixture);
  const first = snapshot.results[0];
  return {
    search_execution_id: snapshot.search_execution_id,
    query_text: '당시 검색어',
    explicit_filters: {},
    created_at: '2026-08-01T03:00:00Z',
    status: 'succeeded',
    snapshot_status: 'available',
    result_count: 1,
    representative_result: Object.fromEntries(
      [
        'search_result_id',
        'scene_id',
        'clip_id',
        'display_name',
        'scene_description',
        'start_time_ms',
        'end_time_ms',
        'rank',
      ].map((key) => [key, first[key]]),
    ),
    search_snapshot: snapshot,
    ...overrides,
  };
}
function pageFixture(items, overrides = {}) {
  return {
    items,
    page: 0,
    size: 10,
    total_elements: items.length,
    total_pages: items.length ? 1 : 0,
    has_next: false,
    ...overrides,
  };
}

test('목록의 빈 기록·0건 검색·복원 불가와 필터 null을 구분한다', () => {
  assert.equal(parseMySearchHistoryPage(pageFixture([])).totalElements, 0);
  const zero = detailFixture({ result_count: 0, representative_result: null });
  zero.search_snapshot.results = [];
  assert.equal(parseMySearchHistoryDetail(zero).searchSnapshot.results.length, 0);
  const missing = detailFixture({
    snapshot_status: 'unavailable',
    result_count: null,
    representative_result: null,
    search_snapshot: null,
    explicit_filters: null,
  });
  assert.equal(parseMySearchHistoryDetail(missing).explicitFilters, null);
  missing.explicit_filters = {};
  assert.deepEqual(parseMySearchHistoryDetail(missing).explicitFilters, {});
  const parsed = parseMySearchHistoryPage(
    pageFixture([zero, { ...missing, search_execution_id: '102' }]),
  );
  assert.equal(parsed.items[0].resultCount, 0);
  assert.equal(parsed.items[1].resultCount, null);
});

test('큰 문자열 ID·제목과 근거 null·순위·degraded 상태를 당시 값으로 보존한다', () => {
  const data = detailFixture({ search_execution_id: '9007199254740993', status: 'degraded' });
  const snapshot = data.search_snapshot;
  snapshot.search_execution_id = data.search_execution_id;
  snapshot.status = 'degraded';
  snapshot.degraded_reasons = ['resolver_fallback'];
  snapshot.query_resolution_status = 'fallback';
  snapshot.has_applied_review_rule = true;
  snapshot.results[0].display_name = null;
  snapshot.results[0].match_evidence[0].value = null;
  data.representative_result.display_name = null;
  const parsed = parseMySearchHistoryDetail(data);
  assert.equal(parsed.searchExecutionId, '9007199254740993');
  assert.equal(parsed.searchSnapshot.results[0].displayName, null);
  assert.equal(parsed.searchSnapshot.results[0].matchEvidence[0].value, null);
  const view = presentSearchResponse(parsed.searchSnapshot);
  assert.equal(view.results[0].clip, '제목 없는 영상');
  assert.equal(view.results[0].evidence, '근거 내용 기록 없음');
  assert.equal(view.results[0].sceneStart, 1.25);
  assert.deepEqual(view.execution.degradedReasons, ['resolver-fallback']);
  assert.equal(view.execution.hasAppliedReviewRule, true);
  assert.throws(() => parseSearchResponse(snapshot), ApiClientError);
});

test('상세의 ID·상태·개수·대표 장면과 스냅샷이 어긋나면 오류로 거른다', () => {
  const changes = [
    (data) => {
      data.search_snapshot.search_execution_id = '999';
    },
    (data) => {
      data.status = 'degraded';
    },
    (data) => {
      data.result_count = 2;
    },
    (data) => {
      data.representative_result.scene_id = '99';
    },
    (data) => {
      data.representative_result.display_name = '현재 제목으로 바꾼 값';
    },
    (data) => {
      data.search_snapshot = null;
    },
    (data) => {
      data.explicit_filters = null;
    },
    (data) => {
      data.representative_result = null;
    },
    (data) => {
      data.snapshot_status = 'unavailable';
    },
    (data) => {
      data.search_snapshot.results[0].shot_type = 'legacy';
    },
    (data) => {
      delete data.search_snapshot.results[0].match_evidence[0].value;
    },
  ];
  for (const change of changes) {
    const data = detailFixture();
    change(data);
    assert.throws(() => parseMySearchHistoryDetail(data), ApiClientError);
  }
});

test('날짜 필터의 누락·불가능 날짜·역순을 빈 조건으로 바꾸지 않는다', () => {
  for (const filters of [
    undefined,
    [],
    { broadcast_date: null },
    { broadcast_date: { from: '2026-09-01' } },
    { filmed_date: { from: '2026-02-30', to: '2026-03-01' } },
    { broadcast_date: { from: '2026-09-02', to: '2026-09-01' } },
  ]) {
    assert.throws(
      () => parseMySearchHistoryDetail(detailFixture({ explicit_filters: filters })),
      ApiClientError,
    );
  }
});

test('페이지 경계와 서버 순서는 유지하고 모순된 목록을 거절한다', () => {
  const items = [
    detailFixture({ search_execution_id: '200' }),
    detailFixture({ search_execution_id: '100' }),
  ];
  assert.deepEqual(
    parseMySearchHistoryPage(pageFixture(items)).items.map((i) => i.searchExecutionId),
    ['200', '100'],
  );
  assert.equal(
    parseMySearchHistoryPage(pageFixture([], { page: 3, total_elements: 12, total_pages: 2 })).page,
    3,
  );
  for (const overrides of [
    { page: -1 },
    { size: 101 },
    { total_pages: 8 },
    { has_next: true },
    { total_elements: 3 },
  ]) {
    assert.throws(() => parseMySearchHistoryPage(pageFixture(items, overrides)), ApiClientError);
  }
  assert.throws(() => parseMySearchHistoryPage(pageFixture([items[0], items[0]])), ApiClientError);
  assert.notDeepEqual(mySearchHistoryKeys.list('1', 0), mySearchHistoryKeys.list('2', 0));
});

test('조회는 세션과 취소 신호로 GET만 호출하고 응답 ID와 페이지를 검증한다', async () => {
  const originalFetch = globalThis.fetch;
  const controller = new AbortController();
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push(String(url));
    assert.equal(init.method ?? 'GET', 'GET');
    assert.equal(init.credentials, 'include');
    assert.equal(init.signal, controller.signal);
    const data = String(url).includes('?') ? pageFixture([detailFixture()]) : detailFixture();
    return new Response(
      JSON.stringify({ isSuccess: true, code: 'COMM_200', message: '성공', data }),
    );
  };
  try {
    assert.equal((await getMySearchHistory(0, controller.signal)).items.length, 1);
    assert.equal(
      (await getMySearchHistoryDetail('100', controller.signal)).queryText,
      '당시 검색어',
    );
    assert.match(requests[0], /\/search\/history\?page=0&size=10$/);
    assert.match(requests[1], /\/search\/history\/100$/);
    await assert.rejects(getMySearchHistory(1, controller.signal), ApiClientError);
    await assert.rejects(getMySearchHistoryDetail('999', controller.signal), ApiClientError);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('전체 삭제는 세션과 취소 신호로 컬렉션에 DELETE 만 호출한다 (S15P21A501-291)', async () => {
  const originalFetch = globalThis.fetch;
  const controller = new AbortController();
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url: String(url), method: init.method, signal: init.signal });
    assert.equal(init.credentials, 'include');
    return new Response(JSON.stringify({ isSuccess: true, code: 'COMM_200', message: '성공' }));
  };
  try {
    await clearMySearchHistory(controller.signal);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'DELETE');
    assert.equal(requests[0].signal, controller.signal);
    // path variable 없는 컬렉션 엔드포인트여야 한다 — /{id} 로 새면 한 건만 지운다.
    assert.match(requests[0].url, /\/search\/history$/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
