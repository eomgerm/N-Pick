'use client';

import { routes } from '@/lib/routes';
import { AppShell } from '@/components/app-shell';

import { CheckCircle2, ChevronDown, ListFilter, Play, Search, Sparkles } from 'lucide-react';
import { useRouter } from 'next/navigation';
import {
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  useMemo,
  useRef,
  useState,
  useTransition,
} from 'react';

import { getKeyframeTimes, results } from '@/features/wireframes/demo-scenes';
import { InquiryDialog, ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import {
  type DateRange,
  emptyDateRange,
  matchesDateRange,
  readDateRange,
} from '@/features/wireframes/date-range';
import { SearchResultState } from '@/features/wireframes/search-result-state';

export interface SearchScreenParams {
  q?: string;
  broadcastFrom?: string;
  broadcastTo?: string;
  filmingFrom?: string;
  filmingTo?: string;
  state?: string;
  preview?: string;
}

interface WireframeShellProps {
  initialQuery?: string;
  theme: WireframeTheme;
  initialParams?: SearchScreenParams;
}

export function WireframeShell({ initialQuery, theme, initialParams = {} }: WireframeShellProps) {
  const router = useRouter();
  const [isNavigating, startNavigation] = useTransition();
  const searchInputRef = useRef<HTMLInputElement>(null);
  const broadcastRange = readDateRange(initialParams.broadcastFrom, initialParams.broadcastTo);
  const filmingRange = readDateRange(initialParams.filmingFrom, initialParams.filmingTo);
  const demoState = initialParams.state;
  const [query, setQuery] = useState(
    (typeof initialQuery === 'string' && initialQuery.trim()) ||
      '2025년 추석 경부고속도로 귀성길 정체',
  );
  const [submittedQuery] = useState(query);
  const [selectedResultId, setSelectedResultId] = useState(1);
  const [isPreviewOpen, setIsPreviewOpen] = useState(initialParams.preview === 'loading');
  const [inquiryResultId, setInquiryResultId] = useState<number | null>(null);
  const [submittedInquiryIds, setSubmittedInquiryIds] = useState<number[]>([]);
  const [sortOrder, setSortOrder] = useState<'accuracy' | 'latest'>('accuracy');

  const selectedResult = useMemo(
    () => results.find(({ id }) => id === selectedResultId) ?? results[0],
    [selectedResultId],
  );
  const inquiryResult = results.find(({ id }) => id === inquiryResultId);
  const filteredResults = results.filter(
    (result) =>
      matchesDateRange(result.broadcastDate, broadcastRange) &&
      matchesDateRange(result.filmingDate, filmingRange, result.filmingState === 'verified'),
  );
  const displayedResults = demoState === 'empty' ? [] : filteredResults;
  const resultState = isNavigating
    ? 'loading'
    : demoState === 'failed'
      ? 'failed'
      : displayedResults.length === 0
        ? 'empty'
        : 'populated';
  const sortedResults =
    sortOrder === 'accuracy'
      ? displayedResults
      : [...displayedResults].sort((a, b) => b.broadcastDate.localeCompare(a.broadcastDate));
  const resolutionTokens = useMemo(
    () => submittedQuery.split(/\s+/).filter(Boolean).slice(0, 3),
    [submittedQuery],
  );

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedQuery = query.trim();

    if (normalizedQuery) {
      handleSearchNavigation(normalizedQuery, broadcastRange, filmingRange);
    }
  }

  function handleSearchNavigation(nextQuery: string, broadcast: DateRange, filming: DateRange) {
    if (isNavigating) return;
    const params = new URLSearchParams({ q: nextQuery });
    if (broadcast.from && broadcast.to) {
      params.set('broadcastFrom', broadcast.from);
      params.set('broadcastTo', broadcast.to);
    }
    if (filming.from && filming.to) {
      params.set('filmingFrom', filming.from);
      params.set('filmingTo', filming.to);
    }
    startNavigation(() =>
      router.push(`${routes.searchResults}?${params.toString()}`, { scroll: false }),
    );
  }

  const rangeFields = (
    <>
      <DateRangePicker
        label="방송일"
        value={broadcastRange}
        isDisabled={isNavigating}
        onChange={(range) => handleSearchNavigation(submittedQuery, range, filmingRange)}
      />
      <DateRangePicker
        label="촬영일"
        value={filmingRange}
        isDisabled={isNavigating}
        onChange={(range) => handleSearchNavigation(submittedQuery, broadcastRange, range)}
      />
    </>
  );

  function handleInquirySubmit() {
    if (inquiryResultId === null) {
      return;
    }

    setSubmittedInquiryIds((current) =>
      current.includes(inquiryResultId) ? current : [...current, inquiryResultId],
    );
    setInquiryResultId(null);
  }

  function handlePreviewSelect(resultId: number) {
    setSelectedResultId(resultId);
    setIsPreviewOpen(true);
  }

  function handlePreviewClose() {
    setIsPreviewOpen(false);
  }

  function handlePreviewInquiry() {
    setIsPreviewOpen(false);
    setInquiryResultId(selectedResult.id);
  }

  return (
    <AppShell className={styles.shell} data-theme={theme}>
      <div className={`${styles.workspace} ${styles.workspaceNoPreview}`}>
        <aside className={styles.filterRail} aria-label="검색 필터">
          <div className={styles.railHeading}>
            <ListFilter aria-hidden="true" />
            <strong>상세 필터</strong>
          </div>
          {rangeFields}
          <fieldset className={styles.filterGroup}>
            <legend>검색할 내용</legend>
            {['장면 설명', '화면 속 글자', '음성 내용'].map((label) => (
              <label key={label}>
                <input defaultChecked type="checkbox" />
                <span>{label}</span>
              </label>
            ))}
          </fieldset>
          <div className={styles.railNote}>
            <CheckCircle2 aria-hidden="true" />
            <span>검증된 날짜 충돌만 결과에서 제외됩니다.</span>
          </div>
        </aside>

        <main className={styles.mainContent}>
          <section className={styles.searchIntro}>
            <div className={styles.titleBlock}>
              <p className={styles.eyebrow}>SCENE SEARCH</p>
              <h1>필요한 뉴스 장면을 바로 찾으세요</h1>
              <p>원고 문장이나 장면의 특징을 입력하면 영상 속 몇 초까지 찾아드립니다.</p>
            </div>

            <form className={styles.searchForm} onSubmit={handleSearch}>
              <label className={styles.searchField}>
                <Search aria-hidden="true" />
                <span className={styles.visuallyHidden}>검색어</span>
                <input
                  aria-label="뉴스 장면 검색어"
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="예: 2025년 추석 경부고속도로 귀성길 정체"
                  value={query}
                  ref={searchInputRef}
                />
              </label>
              <button
                className={styles.searchButton}
                disabled={!query.trim() || isNavigating}
                type="submit"
              >
                <Search aria-hidden="true" />
                <span>{isNavigating ? '검색 중' : '검색'}</span>
              </button>
            </form>
          </section>

          <section className={styles.resolution} aria-label="검색 해석">
            <div className={styles.resolutionIcon}>
              <Sparkles aria-hidden="true" />
            </div>
            <div>
              <span>검색 해석</span>
              <strong>{submittedQuery}</strong>
            </div>
            <div className={styles.resolutionTokens}>
              {resolutionTokens.map((token, index) => (
                <span key={`${token}-${index}`}>{token}</span>
              ))}
            </div>
            <span className={styles.searchHealth}>
              {resultState === 'populated' || resultState === 'empty' ? (
                <CheckCircle2 aria-hidden="true" />
              ) : null}
              {resultState === 'failed'
                ? '검색 연결 실패'
                : resultState === 'loading'
                  ? '검색 중'
                  : '정상 검색'}
            </span>
          </section>

          <section className={styles.resultsSection} id="search-results">
            <div className={styles.resultsHeading}>
              <div>
                <p>검색 결과</p>
                <h2>
                  {resultState === 'failed'
                    ? '검색을 완료하지 못했어요'
                    : resultState === 'loading'
                      ? '검색 중'
                      : `관련 장면 ${displayedResults.length}개`}
                </h2>
              </div>
              <div className={styles.resultsMeta}>
                <span>화면 미리보기 · 예시 데이터</span>
                <label className={styles.sortControl}>
                  <span className={styles.visuallyHidden}>검색 결과 정렬</span>
                  <select
                    value={sortOrder}
                    onChange={(event) =>
                      setSortOrder(event.target.value === 'latest' ? 'latest' : 'accuracy')
                    }
                    disabled={resultState !== 'populated'}
                  >
                    <option value="accuracy">정확도순</option>
                    <option value="latest">최신순</option>
                  </select>
                  <ChevronDown aria-hidden="true" />
                </label>
              </div>
            </div>

            {resultState !== 'populated' ? (
              <SearchResultState
                state={resultState}
                query={submittedQuery}
                broadcastRange={broadcastRange}
                filmingRange={filmingRange}
                excludedCount={results.length - filteredResults.length}
                onReset={() =>
                  handleSearchNavigation(submittedQuery, emptyDateRange, emptyDateRange)
                }
                onRetry={() => handleSearchNavigation(submittedQuery, broadcastRange, filmingRange)}
                onEditQuery={() => {
                  searchInputRef.current?.focus();
                  searchInputRef.current?.select();
                }}
              />
            ) : (
              <div className={styles.resultsGrid}>
                {sortedResults.map((result, index) => {
                  const isSelected = isPreviewOpen && selectedResultId === result.id;
                  const keyframeTimes = getKeyframeTimes(result);

                  return (
                    <article
                      aria-label={`${result.title} Preview 열기`}
                      className={`${styles.resultCard} ${isSelected ? styles.selectedCard : ''}`}
                      key={result.id}
                      onClick={() => handlePreviewSelect(result.id)}
                      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
                        if (event.key !== 'Enter' && event.key !== ' ') return;
                        event.preventDefault();
                        handlePreviewSelect(result.id);
                      }}
                      role="button"
                      tabIndex={0}
                    >
                      <div
                        aria-hidden="true"
                        className={`${styles.thumbnail} ${result.imageClass}`}
                      >
                        <span className={styles.rank}>{index + 1}</span>
                        <span className={styles.playButton}>
                          <Play aria-hidden="true" fill="currentColor" />
                        </span>
                        <span className={styles.timecode}>{result.time}</span>
                        <span className={styles.keyframePreview}>
                          {keyframeTimes.map((keyframeTime) => (
                            <span
                              className={`${styles.keyframe} ${result.imageClass}`}
                              key={`${result.id}-${keyframeTime}`}
                            >
                              <span className={styles.keyframeTime}>{keyframeTime}</span>
                            </span>
                          ))}
                        </span>
                      </div>

                      <div className={styles.cardBody}>
                        <div className={styles.cardTopline}>
                          <span className={styles.score}>일치도 {result.score}%</span>
                          <h3 className={styles.cardTitle}>{result.title}</h3>
                        </div>
                        <div className={styles.cardMetadata}>
                          <p className={styles.filmingDate}>
                            <span>촬영일</span>
                            <strong>{result.filmingDate}</strong>
                          </p>
                          <div className={styles.matchedKeywords}>
                            <span>키워드</span>
                            {result.matchedKeywords.map((keyword) => (
                              <span className={styles.keywordChip} key={keyword}>
                                {keyword}
                              </span>
                            ))}
                          </div>
                        </div>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </main>
      </div>

      <div aria-live="polite" className={styles.visuallyHidden}>
        {resultState === 'failed'
          ? '검색에 실패했습니다.'
          : resultState === 'loading'
            ? '검색 중입니다.'
            : `${submittedQuery} 검색 결과 ${displayedResults.length}개`}
      </div>

      {isPreviewOpen ? (
        <ScenePreviewDialog
          result={selectedResult}
          theme={theme}
          isSubmitted={submittedInquiryIds.includes(selectedResult.id)}
          onInquiry={handlePreviewInquiry}
          onClose={handlePreviewClose}
          keepLoading={initialParams.preview === 'loading'}
        />
      ) : null}
      {inquiryResult ? (
        <InquiryDialog
          result={inquiryResult}
          theme={theme}
          query={submittedQuery}
          onSubmit={handleInquirySubmit}
          onClose={() => setInquiryResultId(null)}
        />
      ) : null}
    </AppShell>
  );
}
