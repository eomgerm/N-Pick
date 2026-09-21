'use client';

import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { createSearchRequestBody } from '@/features/wireframes/search-api-contract';
import { readDateRange, readDateRangeError } from '@/features/wireframes/date-range';
import { ApiClientError } from '@/lib/api/error';
import { presentSearchResponse, searchScenes } from '@/features/wireframes/search-results-api';

export function SearchResults({ params }: { params: SearchScreenParams }) {
  const body = createSearchRequestBody({
    query: params.q ?? '',
    broadcast: readDateRange(params.broadcastFrom, params.broadcastTo),
    filming: readDateRange(params.filmingFrom, params.filmingTo),
  });
  // 고르다 만 기간은 조건이 없었던 것과 다르다. readDateRange 가 접은 값을 그대로 검색하면
  // 사용자가 건 방송일·촬영일이 사라진 채 결과가 나오고, 화면에는 그 사실이 남지 않는다.
  // 계약 §5 는 한쪽만 온 기간을 SRCH_400_003 으로 막으므로 그 요청을 보내지 않는다.
  const rangeError = useMemo(() => {
    const message =
      readDateRangeError(params.broadcastFrom, params.broadcastTo) ||
      readDateRangeError(params.filmingFrom, params.filmingTo);
    return message
      ? new ApiClientError('api', 0, { code: 'CLIENT_DATE_RANGE_INVALID', message })
      : undefined;
  }, [params.broadcastFrom, params.broadcastTo, params.filmingFrom, params.filmingTo]);
  const isBlocked = body === null || rangeError !== undefined;
  const search = useQuery({
    queryKey: ['scene-search', body],
    queryFn: ({ signal }) => searchScenes(body!, signal),
    enabled: !isBlocked,
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
        state: isBlocked || search.isError ? 'failed' : search.isFetching ? 'loading' : 'ready',
        error: rangeError ?? search.error,
        failureReason: rangeError?.message,
        retry: () => {
          if (!isBlocked) void search.refetch();
        },
      }}
    />
  );
}
