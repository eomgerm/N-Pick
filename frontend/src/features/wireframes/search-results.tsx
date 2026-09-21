'use client';

import { useQuery } from '@tanstack/react-query';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { createSearchRequestBody } from '@/features/wireframes/search-api-contract';
import { readDateRange } from '@/features/wireframes/date-range';
import { presentSearchResponse, searchScenes } from '@/features/wireframes/search-results-api';

export function SearchResults({ params }: { params: SearchScreenParams }) {
  const broadcast = readDateRange(params.broadcastFrom, params.broadcastTo);
  const filming = readDateRange(params.filmingFrom, params.filmingTo);
  // 고르다 만 기간은 조건이 없었던 것과 다르다. 접힌 값을 그대로 검색하면 사용자가 건
  // 방송일·촬영일이 사라진 채 결과가 나오고, 화면에는 그 사실이 남지 않는다. 계약 §5 는
  // 한쪽만 온 기간을 SRCH_400_003 으로 막으므로 그 요청을 보내지 않는다.
  const rangeError = broadcast.error || filming.error;
  // 기간을 버린 검색은 본문을 만들지 않는다. 만들면 같은 검색어의 무필터 검색과 queryKey 가
  // 같아져, 캐시에 남은 그 결과가 「필터가 버려진 결과」로 흘러나올 자리가 생긴다.
  const body = rangeError
    ? null
    : createSearchRequestBody({
        query: params.q ?? '',
        broadcast: broadcast.range,
        filming: filming.range,
      });
  const search = useQuery({
    queryKey: ['scene-search', body],
    queryFn: ({ signal }) => searchScenes(body!, signal),
    enabled: body !== null,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });
  return (
    <WireframeShell
      initialQuery={params.q}
      initialParams={params}
      theme="shinhan"
      api={{
        presentation: search.data ? presentSearchResponse(search.data) : undefined,
        state: body === null || search.isError ? 'failed' : search.isFetching ? 'loading' : 'ready',
        error: search.error,
        failureReason: rangeError || undefined,
        retry: () => {
          if (body) void search.refetch();
        },
      }}
    />
  );
}
