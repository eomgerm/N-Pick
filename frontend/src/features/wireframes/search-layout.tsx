'use client';

import type { ReactNode } from 'react';

import { AppShell } from '@/components/app-shell';
import type { DateRange } from '@/features/wireframes/date-range';
import { SearchHistory } from '@/features/wireframes/search-history';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';

interface SearchLayoutProps {
  children: ReactNode;
  className: string;
  theme: WireframeTheme;
  isResults?: boolean;
  isDisabled?: boolean;
  searchField?: ReactNode;
  broadcastRange: DateRange;
  filmingRange: DateRange;
  onBroadcastChange: (value: DateRange) => void;
  onFilmingChange: (value: DateRange) => void;
}

export function SearchLayout({
  children,
  className,
  theme,
  isResults = false,
  isDisabled = false,
  searchField,
  broadcastRange,
  filmingRange,
  onBroadcastChange,
  onFilmingChange,
}: SearchLayoutProps) {
  return (
    <AppShell
      className={className}
      data-theme={theme}
      headerTone="light"
      backdropTone={isResults ? 'dark' : 'clear'}
      isBackdropUnveiled={!isResults}
      isInteractionLocked={isDisabled}
      headerContent={searchField}
    >
      {children}
      <SearchHistory
        broadcastRange={broadcastRange}
        filmingRange={filmingRange}
        isDisabled={isDisabled}
        onBroadcastChange={onBroadcastChange}
        onFilmingChange={onFilmingChange}
        theme={theme}
      />
    </AppShell>
  );
}
