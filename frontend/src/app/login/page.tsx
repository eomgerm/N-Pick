import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

import { LoginShell } from '@/features/wireframes/login-shell';

export const metadata: Metadata = { title: '로그인 | N-Pick' };

interface LoginPageProps {
  searchParams: Promise<{ role?: string | string[] }>;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const { role = 'editor' } = await searchParams;
  if (role !== 'editor' && role !== 'reviewer') notFound();
  return <LoginShell key={role} role={role} theme="shinhan" />;
}
