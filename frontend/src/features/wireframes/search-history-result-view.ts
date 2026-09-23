import type { SearchExplicitFilters } from '@/features/wireframes/search-api-contract';
import type { SearchScreenParams } from '@/features/wireframes/wireframe-shell';

/**
 * 당시 검색 조건(explicit_filters)을 WireframeShell 의 초기 파라미터로 옮긴다.
 *
 * 이걸 안 하면 날짜 선택기가 항상 「전체 기간」으로 열리고, 선택기를 건드리거나 재검색하는
 * 순간 당시 조건이 조용히 버려진다 — 조건이 전혀 없었던 것과 구분되지 않는다.
 */
export function mapExplicitFiltersToInitialParams(
  explicitFilters: SearchExplicitFilters | null,
): SearchScreenParams {
  if (!explicitFilters) return {};
  return {
    broadcastFrom: explicitFilters.broadcast_date?.from,
    broadcastTo: explicitFilters.broadcast_date?.to,
    filmingFrom: explicitFilters.filmed_date?.from,
    filmingTo: explicitFilters.filmed_date?.to,
  };
}

/** 검색 기록 스냅샷임을 알리는 한 줄 배지 문구. createdAt(ISO)을 KST 달력 날짜로 접는다. */
export function formatHistorySnapshotBadge(createdAt: string): string {
  const kstDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(
    new Date(createdAt),
  );
  return `${kstDate} 검색 기록`;
}

/**
 * 스냅샷 뷰 remount 키. 쿼리 문자열이 아니라 "데이터가 도착했는가"로만 정한다 — queryText 값이
 * 우연히 'loading' 이면 문자열 그 자체를 키로 쓸 때 로딩 센티넬과 겹쳐 remount(=initialQuery
 * 반영)가 영원히 일어나지 않는다.
 *
 * 이 remount는 WireframeShell 전체(SearchLayout·AppShell·nav dock 포함)를 다시 만드는
 * 가벼운 동작이 아니므로, "로딩 → 준비" 전환 한 번에만 키가 바뀌도록 값을 둘로만 접는다.
 */
export function historyRemountKey(hasData: boolean): 'ready' | 'loading' {
  return hasData ? 'ready' : 'loading';
}
