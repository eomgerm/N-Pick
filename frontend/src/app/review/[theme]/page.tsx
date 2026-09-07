import { notFound } from 'next/navigation';
import { Suspense } from 'react';

import { ReviewerShell } from '@/features/wireframes/reviewer-shell';
import { isWireframeTheme, wireframeThemes } from '@/features/wireframes/wireframe-themes';

interface ReviewerPageProps {
  params: Promise<{ theme: string }>;
}

export function generateStaticParams() {
  return wireframeThemes.map(({ id }) => ({ theme: id }));
}

export default async function ReviewerPage({ params }: ReviewerPageProps) {
  const { theme } = await params;

  if (!isWireframeTheme(theme)) {
    notFound();
  }

  return (
    <Suspense
      fallback={
        <main aria-busy="true" className="p-10" role="status">
          문의 목록을 불러오고 있어요.
        </main>
      }
    >
      <ReviewerShell key={theme} theme={theme} />
    </Suspense>
  );
}
