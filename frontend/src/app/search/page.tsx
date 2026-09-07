import type { Metadata } from 'next';

import { SearchEntryShell } from '@/features/wireframes/search-entry-shell';

export const metadata: Metadata = { title: '장면 검색 | N-Pick' };

export default function SearchPage() {
  return <SearchEntryShell theme="shinhan" />;
}
