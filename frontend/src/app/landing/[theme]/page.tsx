import { notFound, redirect } from 'next/navigation';

import { isWireframeTheme, wireframeThemes } from '@/features/wireframes/wireframe-themes';

interface LandingPageProps {
  params: Promise<{ theme: string }>;
}

export function generateStaticParams() {
  return wireframeThemes.map(({ id }) => ({ theme: id }));
}

export default async function LandingPage({ params }: LandingPageProps) {
  const { theme } = await params;

  if (!isWireframeTheme(theme)) {
    notFound();
  }

  redirect('/landing');
}
