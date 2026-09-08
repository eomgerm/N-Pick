import { notFound } from 'next/navigation';

import { WireframeShell, type SearchScreenParams } from '@/features/wireframes/wireframe-shell';
import { isWireframeTheme, wireframeThemes } from '@/features/wireframes/wireframe-themes';

interface WireframePageProps {
  params: Promise<{ theme: string }>;
  searchParams: Promise<SearchScreenParams>;
}

export function generateStaticParams() {
  return wireframeThemes.map(({ id }) => ({ theme: id }));
}

export default async function WireframePage({ params, searchParams }: WireframePageProps) {
  const { theme } = await params;
  const queryParams = Object.fromEntries(
    Object.entries(await searchParams).filter(([, value]) => typeof value === 'string'),
  ) as SearchScreenParams;
  const { q } = queryParams;

  if (!isWireframeTheme(theme)) {
    notFound();
  }

  return (
    <WireframeShell
      key={JSON.stringify(queryParams)}
      initialQuery={q}
      initialParams={queryParams}
      theme={theme}
    />
  );
}
