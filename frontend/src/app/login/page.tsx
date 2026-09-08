import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

import { LoginShell } from '@/features/wireframes/login-shell';
import { safeReturnTo } from '@/lib/auth/member';

export const metadata: Metadata = { title: '로그인 | N-Pick' };

interface LoginPageProps {
  searchParams: Promise<{
    role?: string | string[];
    returnTo?: string | string[];
    reason?: string | string[];
  }>;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const { role = 'editor', returnTo, reason } = await searchParams;
  if (role !== 'editor' && role !== 'reviewer') notFound();
  return (
    <LoginShell
      key={role}
      role={role}
      theme="shinhan"
      returnTo={safeReturnTo(returnTo)}
      reason={typeof reason === 'string' ? reason : undefined}
    />
  );
}
