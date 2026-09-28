import type { Metadata } from 'next';
import { redirect } from 'next/navigation';

import { LandingShell } from '@/features/wireframes/landing-shell';
import { postLoginPath } from '@/lib/auth/member';
import { currentMember } from '@/lib/auth/server';

export const metadata: Metadata = {
  title: 'N-Pick | 필요한 순간, 정확한 선택',
};

export default async function LandingPage() {
  const member = await currentMember();
  if (member) redirect(postLoginPath(member.role));

  return <LandingShell />;
}
