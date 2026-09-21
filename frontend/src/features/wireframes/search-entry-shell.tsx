'use client';

import { ArrowRight, Search, X } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState, useTransition } from 'react';

import { emptyDateRange } from '@/features/wireframes/date-range';
import { EntryFooter } from '@/features/wireframes/entry-chrome';
import { SearchLayout } from '@/features/wireframes/search-layout';
import { prepareSearchTransition } from '@/features/wireframes/search-transition';
import { SEARCH_QUERY_MIN_LENGTH } from '@/features/wireframes/search-api-contract';
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

  const trimmedQueryLength = query.trim().length;
  const showMinLengthHint =
    trimmedQueryLength >= 1 && trimmedQueryLength < SEARCH_QUERY_MIN_LENGTH;

  return (
    <SearchLayout
      className={`${styles.shell} ${styles.searchShell}`}
      theme={theme}
      broadcastRange={broadcastRange}
      filmingRange={filmingRange}
      isDisabled={isNavigating}
      onBroadcastChange={setBroadcastRange}
      onFilmingChange={setFilmingRange}
    >
      <main className={styles.searchMain}>
        <div className={styles.searchIntro}>
          <h1>안녕하세요.</h1>
          <p className={styles.searchDescription}>
            찾고 싶은 뉴스 장면을 자연스럽게 설명해 주세요.
          </p>
        </div>
        <div className={styles.searchHero}>
          <form
            aria-busy={isNavigating}
            aria-label="뉴스 장면 검색"
            className={styles.searchPanel}
            onSubmit={handleSubmit}
            role="search"
          >
            <div className={styles.searchForm} ref={searchFieldRef}>
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
                disabled={trimmedQueryLength < SEARCH_QUERY_MIN_LENGTH || isNavigating}
                type="submit"
              >
                <ArrowRight aria-hidden="true" />
              </button>
            </div>
            {showMinLengthHint ? (
              <p className={styles.searchHint} role="alert">
                검색어는 {SEARCH_QUERY_MIN_LENGTH}글자 이상 입력해 주세요.
              </p>
            ) : null}
            <p aria-live="polite" className={styles.srOnly}>
              {isNavigating ? '검색 중입니다. 검색 결과 화면을 준비하고 있습니다.' : ''}
            </p>
          </form>
        </div>
      </main>
      <EntryFooter />
    </SearchLayout>
  );
}
