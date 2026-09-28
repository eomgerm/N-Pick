import type { Metadata } from 'next';

import { SearchEntryShell } from '@/features/wireframes/search-entry-shell';
import { SessionBoundary } from '@/components/session-boundary';
import { requireMember } from '@/lib/auth/server';

export const metadata: Metadata = { title: '장면 검색 | N-Pick' };

export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<{ notice?: string | string[] }>;
}) {
  const member = await requireMember('/search');
  const { notice } = await searchParams;
  return (
    <SessionBoundary member={member}>
      {notice === 'forbidden' && (
        <p className="bg-amber-50 p-4 text-center text-sm text-amber-950" role="status">
          검수 워크스페이스는 검수자 계정만 사용할 수 있습니다. 검색은 계속 이용할 수 있어요.
        </p>
      )}
      <SearchEntryShell theme="shinhan" />
    </SessionBoundary>
  );
}
