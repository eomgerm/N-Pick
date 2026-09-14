'use client';

import { useQuery } from '@tanstack/react-query';
import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { createSearchRequestBody } from '@/features/wireframes/search-api-contract';
import { readDateRange } from '@/features/wireframes/date-range';
import { presentSearchResponse, searchScenes } from '@/features/wireframes/search-results-api';

export function SearchResults({ params }: { params: SearchScreenParams }) {
  const body = createSearchRequestBody({
    query: params.q ?? '',
    broadcast: readDateRange(params.broadcastFrom, params.broadcastTo),
    filming: readDateRange(params.filmingFrom, params.filmingTo),
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
        retry: () => {
          if (body) void search.refetch();
        },
      }}
    />
  );
}
