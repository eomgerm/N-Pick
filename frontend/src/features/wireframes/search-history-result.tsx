'use client';

import { useQuery } from '@tanstack/react-query';

import { useMember } from '@/components/session-boundary';
import { WireframeShell } from '@/features/wireframes/wireframe-shell';
import {
  getMySearchHistoryDetail,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
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

  return (
    <WireframeShell
      // WireframeShell 은 initialQuery 를 마운트 때 한 번만 검색어 입력 초기값으로 읽는다.
      // 스냅샷은 비동기로 오므로, 검색어가 도착하면 key 로 remount 해 입력칸에 당시 검색어를 채운다.
      key={detail.data?.queryText ?? 'loading'}
      initialQuery={detail.data?.queryText}
      theme="shinhan"
      api={{
        presentation,
        // 스냅샷이 없으면(unavailable) 결과가 정말 0건인 것과 구분해 실패로 안내한다 —
        // 빈 결과 화면은 「조건에 맞는 장면이 없어요」라 당시 결과가 없던 것처럼 오인된다.
        state:
          detail.isError || snapshotUnavailable
            ? 'failed'
            : detail.isLoading
              ? 'loading'
              : 'ready',
        error: detail.error,
        failureReason: snapshotUnavailable
          ? '이 검색의 당시 결과 기록이 없어 결과를 표시할 수 없어요.'
          : undefined,
        retry: () => void detail.refetch(),
      }}
    />
  );
}
