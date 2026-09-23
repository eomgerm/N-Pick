'use client';

import { useQuery } from '@tanstack/react-query';

import { useMember } from '@/components/session-boundary';
import { WireframeShell } from '@/features/wireframes/wireframe-shell';
import {
  getMySearchHistoryDetail,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
import {
  formatHistorySnapshotBadge,
  historyRemountKey,
  mapExplicitFiltersToInitialParams,
} from '@/features/wireframes/search-history-result-view';
import { presentSearchResponse } from '@/features/wireframes/search-results-api';

// 검색 기록 클릭 시 당시 저장된 결과 스냅샷을 결과 화면 레이아웃으로 그대로 보여준다 (S15P21A501-262 후속).
// 새 검색을 돌리지 않는다 — GET /search/history/{id} 의 스냅샷을 결과 화면 컴포넌트에 그대로 먹인다.
// 스냅샷은 고정이라 더보기(다음 페이지)가 없다: hasMore 계열을 넘기지 않아 버튼이 숨는다.
export function SearchHistoryResults({ executionId }: { executionId: string }) {
  const { memberId } = useMember();
  const detail = useQuery({
    queryKey: mySearchHistoryKeys.detail(memberId, executionId),
    queryFn: ({ signal }) => getMySearchHistoryDetail(executionId, signal),
    staleTime: 0,
    retry: false,
  });

  const snapshot = detail.data?.searchSnapshot;
  const presentation = snapshot ? presentSearchResponse(snapshot) : undefined;
  // 스냅샷 저장이 없던 기록(snapshotStatus === 'unavailable')은 당시 결과를 복원할 수 없다.
  const snapshotUnavailable = detail.isSuccess && detail.data.searchSnapshot === null;
  // 상세 조회 자체가 실패하면(재시도 없이 최초 실패 포함) 이 화면은 검색어·기간을 전혀 모른다 —
  // 「입력한 검색어와 기간은 유지돼요」라 안내하면 거짓이 된다.
  const conditionsUnknown = detail.isError && !detail.data;

  return (
    <WireframeShell
      // WireframeShell 은 initialQuery/initialParams 를 마운트 때 한 번만 초기값으로 읽는다.
      // 스냅샷은 비동기로 오므로, 데이터가 도착하면 key 로 remount 해 검색어·기간을 채운다.
      // 이 remount는 SearchLayout·AppShell·nav dock 까지 통째로 새로 만드는 가벼운 동작이 아니므로,
      // "로딩 → 준비" 전환에서 한 번만 일어나도록 queryText 값이 아니라 데이터 도착 여부로 키를 정한다
      // (queryText 가 우연히 'loading' 이면 문자열 키는 절대 안 바뀐다).
      key={historyRemountKey(Boolean(detail.data))}
      initialQuery={detail.data?.queryText}
      initialParams={mapExplicitFiltersToInitialParams(detail.data?.explicitFilters ?? null)}
      historyBadge={detail.data ? formatHistorySnapshotBadge(detail.data.createdAt) : undefined}
      // 배지(날짜)는 기록이 있으면 늘 사실이라 그대로 두고, 「저장된 당시 결과예요」 고지는
      // 스냅샷이 실제로 복원됐을 때만 낸다 — unavailable 기록에선 아래 실패 안내와 모순된다.
      showHistoryNotice={Boolean(detail.data) && !snapshotUnavailable}
      theme="shinhan"
      api={{
        presentation,
        // 스냅샷이 없으면(unavailable) 결과가 정말 0건인 것과 구분해 실패로 안내한다 —
        // 빈 결과 화면은 「조건에 맞는 장면이 없어요」라 당시 결과가 없던 것처럼 오인된다.
        state:
          detail.isError || snapshotUnavailable ? 'failed' : detail.isLoading ? 'loading' : 'ready',
        error: detail.error,
        failureReason: snapshotUnavailable
          ? '이 검색의 당시 결과 기록이 없어 결과를 표시할 수 없어요.'
          : undefined,
        conditionsUnknown,
        retry: () => void detail.refetch(),
      }}
    />
  );
}
