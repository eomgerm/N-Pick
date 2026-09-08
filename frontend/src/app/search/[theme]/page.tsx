import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

import { SearchEntryShell } from '@/features/wireframes/search-entry-shell';
import { isWireframeTheme, wireframeThemes } from '@/features/wireframes/wireframe-themes';

export const metadata: Metadata = { title: '장면 검색 | N-Pick' };

interface SearchPageProps {
  params: Promise<{ theme: string }>;
}

export function generateStaticParams() {
  return wireframeThemes.map(({ id }) => ({ theme: id }));
}

export default async function SearchPage({ params }: SearchPageProps) {
  const { theme } = await params;
  if (!isWireframeTheme(theme)) notFound();
  return <SearchEntryShell theme={theme} />;
}
