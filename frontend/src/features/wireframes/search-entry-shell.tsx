'use client';

import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState, useTransition } from 'react';

import { emptyDateRange } from '@/features/wireframes/date-range';
import { EntryFooter } from '@/features/wireframes/entry-chrome';
import { SceneSearchField } from '@/features/wireframes/scene-search-field';
import { SearchLayout } from '@/features/wireframes/search-layout';
import { prepareSearchTransition } from '@/features/wireframes/search-transition';
import {
  createSearchResultsHref,
  isSameSearchDestination,
} from '@/features/wireframes/search-navigation';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/entry.module.css';

interface SearchEntryShellProps {
  theme: WireframeTheme;
}

export function SearchEntryShell({ theme }: SearchEntryShellProps) {
  const router = useRouter();
  const [isNavigating, startNavigation] = useTransition();
  const [query, setQuery] = useState('');
  const [broadcastRange, setBroadcastRange] = useState(emptyDateRange);
  const [filmingRange, setFilmingRange] = useState(emptyDateRange);
  const searchFieldRef = useRef<HTMLDivElement>(null);
  const navigationLockRef = useRef(false);
  const hasObservedNavigationRef = useRef(false);

  useEffect(() => {
    if (isNavigating) {
      hasObservedNavigationRef.current = true;
      return;
    }
    if (hasObservedNavigationRef.current) {
      navigationLockRef.current = false;
      hasObservedNavigationRef.current = false;
    }
  }, [isNavigating]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (navigationLockRef.current || isNavigating) return;

    const href = createSearchResultsHref({
      query,
      broadcast: broadcastRange,
      filming: filmingRange,
    });
    if (!href) return;
    if (
      typeof window !== 'undefined' &&
      isSameSearchDestination(`${window.location.pathname}${window.location.search}`, href)
    )
      return;

    navigationLockRef.current = true;
    const cancelTransition = prepareSearchTransition(searchFieldRef.current, href);
    try {
      startNavigation(() => router.push(href));
    } catch (error) {
      cancelTransition();
      navigationLockRef.current = false;
      throw error;
    }
  }

  return (
    <SearchLayout
      className={`${styles.shell} ${styles.searchShell}`}
      theme={theme}
      broadcastRange={broadcastRange}
      filmingRange={filmingRange}
      isDisabled={isNavigating}
      onDateRangesChange={({ broadcast, filming }) => {
        setBroadcastRange(broadcast);
        setFilmingRange(filming);
      }}
      onSearchHistorySelect={(historyQuery) => {
        setQuery(historyQuery);
        requestAnimationFrame(() => searchFieldRef.current?.querySelector('input')?.focus());
      }}
    >
      <main className={styles.searchMain}>
        <div className={styles.searchIntro}>
          <h1>안녕하세요.</h1>
          <p className={styles.searchDescription}>
            찾고 싶은 뉴스 장면을 자연스럽게 설명해 주세요.
          </p>
        </div>
        <div className={styles.searchHero}>
          <SceneSearchField
            variant="hero"
            query={query}
            onQueryChange={setQuery}
            onSubmit={handleSubmit}
            placeholder="예: 비 내리는 출근길 광화문 횡단보도"
            formLabel="뉴스 장면 검색"
            inputLabel="뉴스 장면 검색어"
            isBusy={isNavigating}
            isDisabled={isNavigating}
            fieldRef={searchFieldRef}
            inputId="scene-search"
            classes={{
              form: styles.searchPanel,
              field: styles.searchForm,
              icon: styles.searchIcon,
              surface: styles.searchInputSurface,
              clearButton: styles.clearButton,
              submitButton: styles.primaryButton,
              hint: styles.searchHint,
              srOnly: styles.srOnly,
            }}
          />
        </div>
      </main>
      <EntryFooter />
    </SearchLayout>
  );
}
