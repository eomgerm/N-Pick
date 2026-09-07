import type { Metadata } from 'next';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';

export const metadata: Metadata = { title: '검색 결과 | N-Pick' };

interface SearchResultsPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function SearchResultsPage({ searchParams }: SearchResultsPageProps) {
  const queryParams = Object.fromEntries(
    Object.entries(await searchParams).filter(([, value]) => typeof value === 'string'),
  ) as SearchScreenParams;

  return (
    <WireframeShell
      key={JSON.stringify(queryParams)}
      initialQuery={queryParams.q}
      initialParams={queryParams}
      theme="shinhan"
    />
  );
}
