import type { Metadata } from 'next';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
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

  return (
    <SessionBoundary member={member}>
      <WireframeShell
        key={JSON.stringify(queryParams)}
        initialQuery={queryParams.q}
        initialParams={queryParams}
        theme="shinhan"
      />
    </SessionBoundary>
  );
}
