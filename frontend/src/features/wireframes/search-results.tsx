'use client';

import { useInfiniteQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { createSearchRequestBody } from '@/features/wireframes/search-api-contract';
import { readDateRange } from '@/features/wireframes/date-range';
import {
  mergeSearchExecutions,
  mergeSearchResultDetails,
  presentSearchResponse,
  searchScenes,
} from '@/features/wireframes/search-results-api';
import { validateSearchQuery } from '@/features/wireframes/input-validation';
import { seoulToday } from '@/lib/seoul-date';

const subscribeToNothing = () => () => {};
const getNoToday = () => '';

export function SearchResults({ params }: { params: SearchScreenParams }) {
  const today = useSyncExternalStore(subscribeToNothing, seoulToday, getNoToday);
  const broadcast = readDateRange(params.broadcastFrom, params.broadcastTo, today);
  const filming = readDateRange(params.filmingFrom, params.filmingTo, today);
  // 고르다 만 기간은 조건이 없었던 것과 다르다. 접힌 값을 그대로 검색하면 사용자가 건
  // 방송일·촬영일이 사라진 채 결과가 나오고, 화면에는 그 사실이 남지 않는다. 계약 §5 는
  // 한쪽만 온 기간을 SRCH_400_003 으로 막으므로 그 요청을 보내지 않는다.
  const rangeError = broadcast.error || filming.error;
  // 기간을 버린 검색은 본문을 만들지 않는다. 만들면 같은 검색어의 무필터 검색과 queryKey 가
  // 같아져, 캐시에 남은 그 결과가 「필터가 버려진 결과」로 흘러나올 자리가 생긴다.
  const body =
    !today || rangeError
      ? null
      : createSearchRequestBody({
          query: params.q ?? '',
          broadcast: broadcast.range,
          filming: filming.range,
        });
  const search = useInfiniteQuery({
    queryKey: ['scene-search', body],
    // 첫 페이지(pageParam=0)는 page 를 싣지 않는다 — 계약상 생략이 곧 첫 페이지이고 더보기만
    // page 를 싣는다(search-api-contract.ts:26). 0 도 실으면 첫 요청 본문이 계약과 달라진다.
    queryFn: ({ pageParam, signal }) =>
      searchScenes(pageParam > 0 ? { ...body!, page: pageParam } : body!, signal),
    enabled: body !== null,
    initialPageParam: 0,
    // 다음 페이지 번호 = 지금까지 받은 페이지 수(0-based). has_next 가 거짓이면 더보기를 멈춘다.
    getNextPageParam: (lastPage, allPages) => (lastPage.hasNext ? allPages.length : undefined),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });

  // 페이지를 이어 붙인다. 페이지마다 rank 가 1..10 로 반복되므로 표시 id·rank 를 전체 순번으로
  // 다시 매긴다 — 그대로 두면 카드 key 와 선택 id 가 페이지 간 충돌한다. 실행·상세는 페이지별로
  // 다르므로 첫 페이지만 쓰지 않고 모든 페이지를 병합한다 (S15P21A501-251 리뷰).
  const pages = search.data?.pages ?? [];
  let presentation;
  if (pages.length > 0) {
    const presented = pages.map(presentSearchResponse);
    const results = presented
      .flatMap((page) => page.results)
      .map((result, index) => ({ ...result, id: index + 1, rank: index + 1 }));
    presentation = {
      results,
      execution: mergeSearchExecutions(presented.map((page) => page.execution)),
      details: mergeSearchResultDetails(presented.map((page) => page.details)),
    };
  }

  return (
    <WireframeShell
      initialQuery={params.q}
      initialParams={params}
      theme="shinhan"
      api={{
        validationMessage: validateSearchQuery(params.q ?? ''),
        presentation,
        // 더보기 추가 조회(isFetchingNextPage)는 전체 화면을 loading 으로 바꾸지 않는다 — 첫 로딩만.
        // 첫 페이지가 이미 있으면 더보기 실패로 전체 화면을 failed 로 덮지 않는다 — 불러온 결과를
        // 유지하고 더보기 영역에서만 재시도한다 (S15P21A501-251 리뷰).
        // 검색 실패는 화면을 failed 로 덮는다. 단 더보기(fetchNextPage) 실패는 예외 — 불러온
        // 결과를 유지하고 더보기 영역에서만 재시도한다. 재검색(refetch) 실패는 react-query 가
        // 이전 결과를 남겨도 failed 로 본다. pages.length 로 가르면(옛 코드) 재검색 실패가 남은
        // 결과 뒤에 숨어 「정상」으로 위장된다 (S15P21A501-251 리뷰).
        state: !today
          ? 'loading'
          : body === null || (search.isError && !search.isFetchNextPageError)
            ? 'failed'
            : search.isLoading
              ? 'loading'
              : 'ready',
        // 더보기(fetchNextPage) 실패는 전역 오류 토스트로 띄우지 않는다 — 위 state 와 같은
        // 기준으로, 불러온 결과를 유지하고 더보기 영역에서만 재시도를 안내한다. 여기서 error 를
        // 거르지 않으면 state 만 고치고 토스트가 남아 수정이 반쪽이 된다 (S15P21A501-251 리뷰).
        error: search.isFetchNextPageError ? undefined : search.error,
        // 첫 로딩(isLoading)·더보기(isFetchingNextPage)를 뺀 순수 재조회. 네비게이션 잠금을
        // 재검색 완료 시 풀어 주기 위해 shell 에 알린다 (S15P21A501-251).
        isRevalidating: search.isFetching && !search.isLoading && !search.isFetchingNextPage,
        failureReason: rangeError || undefined,
        retry: () => {
          if (body) void search.refetch();
        },
        hasMore: search.hasNextPage,
        isLoadingMore: search.isFetchingNextPage,
        loadMoreError: search.isFetchNextPageError,
        onLoadMore: () => {
          if (search.hasNextPage) void search.fetchNextPage();
        },
      }}
    />
  );
}
