import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

import { LoginShell } from '@/features/wireframes/login-shell';
import { isWireframeTheme } from '@/features/wireframes/wireframe-themes';

export const metadata: Metadata = { title: '로그인 | N-Pick' };

interface LoginPageProps {
  params: Promise<{ theme: string }>;
  searchParams: Promise<{ role?: string | string[] }>;
}

export default async function LoginPage({ params, searchParams }: LoginPageProps) {
  const { theme } = await params;
  const { role = 'editor' } = await searchParams;
  if (!isWireframeTheme(theme) || (role !== 'editor' && role !== 'reviewer')) notFound();
  return <LoginShell key={`${theme}-${role}`} role={role} theme={theme} />;
}
