import type { Metadata } from 'next';
import { Suspense } from 'react';

import { ReviewerShell } from '@/features/wireframes/reviewer-shell';
import { SessionBoundary } from '@/components/session-boundary';
import { requireMember } from '@/lib/auth/server';
import { pageLocation } from '@/lib/auth/member';

export const metadata: Metadata = { title: '검수 워크스페이스 | N-Pick' };

export default async function ReviewerPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const member = await requireMember(pageLocation('/review', await searchParams));
  return (
    <SessionBoundary member={member}>
      <Suspense
        fallback={
          <main aria-busy="true" className="p-10" role="status">
            문의 목록을 불러오고 있어요.
          </main>
        }
      >
        <ReviewerShell theme="shinhan" />
      </Suspense>
    </SessionBoundary>
  );
}
