'use client';

import { AppShell } from '@/components/app-shell';

import { ArrowRight, Search, X } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState, useTransition } from 'react';

import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import { emptyDateRange } from '@/features/wireframes/date-range';
import { EntryFooter } from '@/features/wireframes/entry-chrome';
import { SearchHistory } from '@/features/wireframes/search-history';
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
  const inputRef = useRef<HTMLInputElement>(null);
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
    try {
      startNavigation(() => router.push(href));
    } catch (error) {
      navigationLockRef.current = false;
      throw error;
    }
  }

  return (
    <AppShell
      className={`${styles.shell} ${styles.searchShell}`}
      data-theme={theme}
      headerTone="light"
      isBackdropUnveiled
    >
      <main className={styles.searchMain}>
        <div className={styles.searchHero}>
          <div className={styles.searchIntro}>
            <h1>안녕하세요.</h1>
            <p className={styles.searchDescription}>
              찾고 싶은 뉴스 장면을 자연스럽게 설명해 주세요.
            </p>
          </div>
          <form
            aria-busy={isNavigating}
            aria-label="뉴스 장면 검색"
            className={styles.searchPanel}
            onSubmit={handleSubmit}
            role="search"
          >
            <div className={styles.searchForm}>
              <Search aria-hidden="true" className={styles.searchIcon} />
              <label className={styles.srOnly} htmlFor="scene-search">
                뉴스 장면 검색어
              </label>
              <span className={styles.searchInputSurface}>
                <input
                  autoComplete="off"
                  disabled={isNavigating}
                  enterKeyHint="search"
                  id="scene-search"
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="예: 비 내리는 출근길 광화문 횡단보도"
                  ref={inputRef}
                  type="search"
                  value={query}
                />
                <button
                  aria-label="검색어 지우기"
                  className={styles.clearButton}
                  disabled={!query || isNavigating}
                  onClick={() => {
                    setQuery('');
                    inputRef.current?.focus();
                  }}
                  type="button"
                >
                  <X aria-hidden="true" />
                </button>
              </span>
              <button
                aria-label={isNavigating ? '검색 중' : '장면 찾기'}
                className={styles.primaryButton}
                disabled={!query.trim() || isNavigating}
                type="submit"
              >
                <ArrowRight aria-hidden="true" />
              </button>
            </div>
            <fieldset
              className={styles.searchFilters}
              disabled={isNavigating}
              onKeyDown={(event) => {
                if (
                  event.key === 'Enter' &&
                  event.target instanceof HTMLInputElement &&
                  event.target.type === 'date'
                ) {
                  event.preventDefault();
                }
              }}
            >
              <legend>
                날짜 필터 <span>선택</span>
              </legend>
              <div className={styles.searchFilterGrid}>
                <DateRangePicker
                  label="방송일"
                  value={broadcastRange}
                  isDisabled={isNavigating}
                  onChange={setBroadcastRange}
                />
                <DateRangePicker
                  label="촬영일"
                  value={filmingRange}
                  isDisabled={isNavigating}
                  onChange={setFilmingRange}
                />
              </div>
            </fieldset>
            <p aria-live="polite" className={styles.srOnly}>
              {isNavigating ? '검색 중입니다. 검색 결과 화면을 준비하고 있습니다.' : ''}
            </p>
          </form>
        </div>
        <SearchHistory theme={theme} />
      </main>
      <EntryFooter />
    </AppShell>
  );
}
