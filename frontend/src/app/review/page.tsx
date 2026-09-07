import type { Metadata } from 'next';
import { Suspense } from 'react';

import { ReviewerShell } from '@/features/wireframes/reviewer-shell';

export const metadata: Metadata = { title: '검수 워크스페이스 | N-Pick' };

export default function ReviewerPage() {
  return (
    <Suspense
      fallback={
        <main aria-busy="true" className="p-10" role="status">
          문의 목록을 불러오고 있어요.
        </main>
      }
    >
      <ReviewerShell theme="shinhan" />
    </Suspense>
  );
}
