import type { Metadata } from 'next';
import { SearchResults } from '@/features/wireframes/search-results';
import { SearchHistoryResults } from '@/features/wireframes/search-history-result';

import type { SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { SessionBoundary } from '@/components/session-boundary';
import { requireMember } from '@/lib/auth/server';
import { pageLocation } from '@/lib/auth/member';

export const metadata: Metadata = { title: '검색 결과 | N-Pick' };

interface SearchResultsPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function SearchResultsPage({ searchParams }: SearchResultsPageProps) {
  const params = await searchParams;
  const member = await requireMember(pageLocation('/search/results', params));
  const queryParams = Object.fromEntries(
    Object.entries(params).filter(([, value]) => typeof value === 'string'),
  ) as SearchScreenParams;

  // 기록 클릭 진입: 새 검색 대신 당시 결과 스냅샷을 결과 화면으로 보여준다 (S15P21A501-262 후속).
  const historyId = typeof params.historyId === 'string' ? params.historyId : null;

  return (
    <SessionBoundary member={member}>
      {historyId ? (
        <SearchHistoryResults key={historyId} executionId={historyId} />
      ) : (
        <SearchResults key={JSON.stringify(queryParams)} params={queryParams} />
      )}
    </SessionBoundary>
  );
}
