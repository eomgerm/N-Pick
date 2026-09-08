import type { Metadata } from 'next';

import { LandingShell } from '@/features/wireframes/landing-shell';

export const metadata: Metadata = {
  title: 'N-Pick | 필요한 순간, 정확한 선택',
};

export default function LandingPage() {
  return <LandingShell />;
}
