'use client';

import { routes } from '@/lib/routes';
import { AppShell } from '@/components/app-shell';

import { AlertTriangle, CheckCircle2, ListFilter, Search, Sparkles } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, useMemo, useRef, useState, useTransition } from 'react';

import { results } from '@/features/wireframes/demo-scenes';
import { InquiryDialog, ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import { SearchResultCard } from '@/features/wireframes/search-result-card';
import type { InquiryButtonState } from '@/features/wireframes/search-result-card';
import {
  createInquirySubmission,
  isSameInquiryRequest,
  submitInquiry,
  type InquirySubmission,
} from '@/features/search/inquiry-api';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import { type DateRange, emptyDateRange, readDateRange } from '@/features/wireframes/date-range';
import { SearchResultState } from '@/features/wireframes/search-result-state';
import {
  canCreateInquiry,
  getDemoSearchExecution,
  getSearchExecutionAnnouncement,
  successfulSearchExecution,
} from '@/features/wireframes/search-execution-status';
import { SearchResultNotices } from '@/features/wireframes/search-result-notices';

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
  const demoSearchExecution = getDemoSearchExecution(demoState);
  const [query, setQuery] = useState(
    (typeof initialQuery === 'string' && initialQuery.trim()) ||
      '2025년 추석 경부고속도로 귀성길 정체',
  );
  const [submittedQuery] = useState(query);
  const [selectedResultId, setSelectedResultId] = useState(1);
  const [isPreviewOpen, setIsPreviewOpen] = useState(initialParams.preview === 'loading');
  const [inquiryResultId, setInquiryResultId] = useState<number | null>(null);
  const [submittedInquiryIds, setSubmittedInquiryIds] = useState<number[]>([]);
  const [inquirySubmission, setInquirySubmission] = useState<InquirySubmission | null>(null);
  const [inquiryError, setInquiryError] = useState<unknown>();
  const [isInquirySubmitting, setIsInquirySubmitting] = useState(false);
  const [inquirySuccessNotice, setInquirySuccessNotice] = useState('');
  const inquirySubmittingRef = useRef(false);

  const selectedResult = useMemo(
    () => results.find(({ id }) => id === selectedResultId) ?? results[0],
    [selectedResultId],
  );
  const inquiryResult = results.find(({ id }) => id === inquiryResultId);
  const displayedResults = demoState === 'empty' ? [] : results;
  const resultState = isNavigating
    ? 'loading'
    : demoState === 'failed'
      ? 'failed'
      : displayedResults.length === 0
        ? 'empty'
        : 'populated';
  const searchExecution =
    resultState === 'populated' ? demoSearchExecution : successfulSearchExecution;
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

  async function handleInquirySubmit(comment: string) {
    if (
      inquirySubmittingRef.current ||
      inquiryResultId === null ||
      !inquiryResult ||
      !canCreateInquiry(searchExecution)
    ) {
      return;
    }

    const submission =
      inquirySubmission && isSameInquiryRequest(inquirySubmission, inquiryResult.resultId, comment)
        ? inquirySubmission
        : createInquirySubmission(inquiryResult.resultId, comment);

    inquirySubmittingRef.current = true;
    setInquirySubmission(submission);
    setInquiryError(undefined);
    setIsInquirySubmitting(true);

    try {
      const response = await submitInquiry(submission);
      setSubmittedInquiryIds((current) =>
        current.includes(inquiryResultId) ? current : [...current, inquiryResultId],
      );
      setInquirySuccessNotice(
        `문의 #${response.inquiryId}가 접수되었습니다. 검수 후 반영되며 현재 검색 결과는 즉시 변경되지 않습니다.`,
      );
      setInquirySubmission(null);
      setInquiryResultId(null);
    } catch (error) {
      setInquiryError(error);
    } finally {
      inquirySubmittingRef.current = false;
      setIsInquirySubmitting(false);
    }
  }

  function getInquiryState(resultId: number): InquiryButtonState {
    if (submittedInquiryIds.includes(resultId)) return 'submitted';
    if (!canCreateInquiry(searchExecution)) return 'unavailable';
    if (isInquirySubmitting && inquiryResultId === resultId) return 'submitting';
    return 'ready';
  }

  function handleInquiryOpen(resultId: number) {
    if (!canCreateInquiry(searchExecution) || submittedInquiryIds.includes(resultId)) return;
    setInquiryResultId(resultId);
    setInquirySubmission(null);
    setInquiryError(undefined);
  }

  function handleInquiryClose() {
    if (inquirySubmittingRef.current) return;
    setInquiryResultId(null);
    setInquirySubmission(null);
    setInquiryError(undefined);
  }

  function handlePreviewSelect(resultId: number) {
    setSelectedResultId(resultId);
    setIsPreviewOpen(true);
  }

  function handlePreviewClose() {
    setIsPreviewOpen(false);
  }

  function handlePreviewInquiry() {
    if (!canCreateInquiry(searchExecution)) return;
    setIsPreviewOpen(false);
    handleInquiryOpen(selectedResult.id);
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
            <span
              className={styles.searchHealth}
              data-status={searchExecution.status === 'degraded' ? 'degraded' : resultState}
            >
              {resultState === 'populated' || resultState === 'empty' ? (
                searchExecution.status === 'degraded' ? (
                  <AlertTriangle aria-hidden="true" />
                ) : (
                  <CheckCircle2 aria-hidden="true" />
                )
              ) : null}
              {resultState === 'failed'
                ? '검색 연결 실패'
                : resultState === 'loading'
                  ? '검색 중'
                  : searchExecution.status === 'degraded'
                    ? '일부 기능 누락'
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
              </div>
            </div>

            {inquirySuccessNotice ? (
              <p className={styles.inquirySuccessNotice} role="status">
                {inquirySuccessNotice}
              </p>
            ) : null}

            {resultState !== 'populated' ? (
              <SearchResultState
                state={resultState}
                query={submittedQuery}
                broadcastRange={broadcastRange}
                filmingRange={filmingRange}
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
              <>
                <SearchResultNotices execution={searchExecution} variant="results" />
                <div className={styles.resultsGrid}>
                  {displayedResults.map((result, index) => (
                    <SearchResultCard
                      result={result}
                      position={index + 1}
                      isSelected={isPreviewOpen && selectedResultId === result.id}
                      inquiryState={getInquiryState(result.id)}
                      inquiryUnavailableReason="검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다."
                      key={result.id}
                      onInquiry={handleInquiryOpen}
                      onSelect={handlePreviewSelect}
                    />
                  ))}
                </div>
              </>
            )}
          </section>
        </main>
      </div>

      <div aria-live="polite" className={styles.visuallyHidden}>
        {resultState === 'failed'
          ? '검색에 실패했습니다.'
          : resultState === 'loading'
            ? '검색 중입니다.'
            : `${submittedQuery} 검색 결과 ${displayedResults.length}개. ${getSearchExecutionAnnouncement(searchExecution)}`}
      </div>

      {isPreviewOpen ? (
        <ScenePreviewDialog
          result={selectedResult}
          theme={theme}
          isSubmitted={submittedInquiryIds.includes(selectedResult.id)}
          isSubmitting={isInquirySubmitting && inquiryResultId === selectedResult.id}
          searchExecution={searchExecution}
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
          error={inquiryError}
          isSubmitting={isInquirySubmitting}
          onCommentChange={() => {
            setInquirySubmission(null);
            setInquiryError(undefined);
          }}
          onSubmit={handleInquirySubmit}
          onClose={handleInquiryClose}
        />
      ) : null}
    </AppShell>
  );
}
