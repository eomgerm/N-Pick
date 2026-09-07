'use client';

import { ArrowRight, Search, X } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, useRef, useState } from 'react';

import { EntryHeader, EntryFooter } from '@/features/wireframes/entry-chrome';
import { SearchHistory } from '@/features/wireframes/search-history';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/entry.module.css';

interface SearchEntryShellProps {
  theme: WireframeTheme;
}

export function SearchEntryShell({ theme }: SearchEntryShellProps) {
  const router = useRouter();
  const [query, setQuery] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (query.trim()) router.push(`/wireframes/${theme}?q=${encodeURIComponent(query.trim())}`);
  }

  return (
    <div className={`${styles.shell} ${styles.searchShell}`} data-theme={theme}>
      <EntryHeader label="편집자 워크스페이스" />
      <main className={styles.searchMain}>
        <div className={styles.searchHero}>
          <p className={styles.eyebrow}>FIND YOUR NEXT SCENE</p>
          <h1>
            오늘 필요한 장면을
            <br />
            <em>바로 찾아볼까요?</em>
          </h1>
          <p className={styles.searchDescription}>
            찾고 싶은 뉴스 장면을 자연스럽게 설명해 주세요.
          </p>
          <form
            aria-label="뉴스 장면 검색"
            className={styles.searchForm}
            onSubmit={handleSubmit}
            role="search"
          >
            <Search aria-hidden="true" className={styles.searchIcon} />
            <label className={styles.srOnly} htmlFor="scene-search">
              뉴스 장면 검색어
            </label>
            <input
              autoComplete="off"
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
              disabled={!query}
              onClick={() => {
                setQuery('');
                inputRef.current?.focus();
              }}
              type="button"
            >
              <X aria-hidden="true" />
            </button>
            <button className={styles.primaryButton} disabled={!query.trim()} type="submit">
              장면 찾기
              <ArrowRight aria-hidden="true" />
            </button>
          </form>
        </div>
        <SearchHistory theme={theme} />
      </main>
      <EntryFooter />
    </div>
  );
}
