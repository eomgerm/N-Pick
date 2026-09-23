import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

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

const { mapExplicitFiltersToInitialParams, formatHistorySnapshotBadge, historyRemountKey } =
  await import('./search-history-result-view.ts');

test('당시 검색 조건을 방송일·촬영일 초기 파라미터로 옮긴다', () => {
  assert.deepEqual(
    mapExplicitFiltersToInitialParams({
      broadcast_date: { from: '2026-09-01', to: '2026-09-03' },
      filmed_date: { from: '2026-08-28', to: '2026-08-29' },
    }),
    {
      broadcastFrom: '2026-09-01',
      broadcastTo: '2026-09-03',
      filmingFrom: '2026-08-28',
      filmingTo: '2026-08-29',
    },
  );
});

test('한쪽 조건만 있었으면 그 조건만 옮기고 나머지는 비워 둔다', () => {
  assert.deepEqual(mapExplicitFiltersToInitialParams({ broadcast_date: { from: 'a', to: 'b' } }), {
    broadcastFrom: 'a',
    broadcastTo: 'b',
    filmingFrom: undefined,
    filmingTo: undefined,
  });
});

test('당시 조건이 없었으면(null) 빈 초기값을 돌려준다 — 전체 기간과 구분해 조용히 버리지 않는다', () => {
  assert.deepEqual(mapExplicitFiltersToInitialParams(null), {});
});

test('검색 기록 배지는 createdAt을 KST 달력 날짜로 접는다', () => {
  assert.equal(formatHistorySnapshotBadge('2026-09-15T20:30:00Z'), '2026-09-16 검색 기록');
  assert.equal(formatHistorySnapshotBadge('2026-09-15T03:00:00Z'), '2026-09-15 검색 기록');
});

test('remount 키는 데이터 도착 여부로만 정해진다 — queryText 값이 아니다', () => {
  assert.equal(historyRemountKey(false), 'loading');
  assert.equal(historyRemountKey(true), 'ready');
  // 회귀 방지: queryText 가 우연히 'loading' 문자열이어도 데이터가 있으면 'ready' 여야 한다.
  // (과거 버그: key={queryText ?? 'loading'} 는 이 경우 키가 안 바뀌어 remount가 일어나지 않았다.)
});
