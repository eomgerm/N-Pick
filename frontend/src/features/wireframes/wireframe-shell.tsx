'use client';

import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ArrowRight, CheckCircle2, Sparkles } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useMemo, useRef, useState, useTransition } from 'react';

import { results as demoResults, type SearchResult } from '@/features/wireframes/demo-scenes';
import { InquiryDialog, ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import { SearchResultCard } from '@/features/wireframes/search-result-card';
import { SearchErrorToast } from '@/features/wireframes/search-error-toast';
import { SearchLayout } from '@/features/wireframes/search-layout';
import { useSearchArrival } from '@/features/wireframes/search-transition';
import {
  createSearchResultsHref,
  isSameSearchDestination,
} from '@/features/wireframes/search-navigation';
import {
  createInquirySubmission,
  isSameInquiryRequest,
  submitInquiry,
  type InquirySubmission,
} from '@/features/wireframes/inquiry-api';
import { inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import { myInquiryKeys } from '@/features/wireframes/my-inquiry-api';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import { type DateRange, emptyDateRange, readDateRange } from '@/features/wireframes/date-range';
import { SearchResultState } from '@/features/wireframes/search-result-state';
import {
  canCreateInquiry,
  getDemoSearchExecution,
  getSearchExecutionAnnouncement,
  successfulSearchExecution,
  type SearchExecutionPresentation,
} from '@/features/wireframes/search-execution-status';
import { SearchResultNotices } from '@/features/wireframes/search-result-notices';
import {
  getResolverLabel,
  type SearchResultDetails,
} from '@/features/wireframes/search-result-details';

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
  api?: {
    presentation?: {
      results: SearchResult[];
      execution: SearchExecutionPresentation;
      details: SearchResultDetails;
    };
    state: 'loading' | 'failed' | 'ready';
    error: unknown;
    /** 실패가 서버 왕복 때문이 아닐 때 그 이유. 일시적 연결 문제로 안내하면 사실이 아니다. */
    failureReason?: string;
    retry: () => void;
  };
  initialQuery?: string;
  theme: WireframeTheme;
  initialParams?: SearchScreenParams;
  execution?: SearchExecutionPresentation;
  resultDetails?: SearchResultDetails;
}

export function WireframeShell({
  initialQuery,
  theme,
  initialParams = {},
  api,
  execution,
  resultDetails,
}: WireframeShellProps) {
  const results = api ? (api.presentation?.results ?? []) : demoResults;
  const router = useRouter();
  const queryClient = useQueryClient();
  const { searchFieldRef, workspaceRef } = useSearchArrival();
  const [isNavigating, startNavigation] = useTransition();
  const navigationLockRef = useRef(false);
  const hasObservedNavigationRef = useRef(false);
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
  const [submittedInquiryIds, setSubmittedInquiryIds] = useState<string[]>([]);
  const isSearchPending = isNavigating || api?.state === 'loading';
  const [inquirySubmission, setInquirySubmission] = useState<InquirySubmission | null>(null);
  const [inquiryError, setInquiryError] = useState<unknown>();
  const [isInquirySubmitting, setIsInquirySubmitting] = useState(false);
  const [inquirySuccessNotice, setInquirySuccessNotice] = useState('');
  const inquirySubmittingRef = useRef(false);

  useEffect(() => {
    if (isSearchPending) {
      hasObservedNavigationRef.current = true;
      return;
    }
    if (hasObservedNavigationRef.current) {
      navigationLockRef.current = false;
      hasObservedNavigationRef.current = false;
    }
  }, [isSearchPending]);

  const selectedResult = useMemo(
    () => results.find(({ id }) => id === selectedResultId) ?? results[0],
    [results, selectedResultId],
  );
  const inquiryResult = results.find(({ id }) => id === inquiryResultId);
  const displayedResults = !api && demoState === 'empty' ? [] : results;
  const resultState =
    isNavigating || api?.state === 'loading'
      ? 'loading'
      : api?.state === 'failed' || (!api && demoState === 'failed')
        ? 'failed'
        : displayedResults.length === 0
          ? 'empty'
          : 'populated';
  const searchExecution =
    resultState === 'populated' || resultState === 'empty'
      ? (api?.presentation?.execution ?? execution ?? demoSearchExecution)
      : successfulSearchExecution;
  const effectiveResultDetails = api?.presentation?.details ?? resultDetails;
  const details: SearchResultDetails = {
    ...effectiveResultDetails,
    resolverStatus:
      searchExecution.status === 'degraded' &&
      searchExecution.degradedReasons.includes('resolver-fallback')
        ? 'fallback'
        : effectiveResultDetails?.resolverStatus,
  };
  const resolutionStatusLabel =
    resultState === 'loading'
      ? '확인 중'
      : resultState === 'failed'
        ? '확인하지 못함'
        : getResolverLabel(details.resolverStatus);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedQuery = query.trim();

    if (normalizedQuery) {
      handleSearchNavigation(normalizedQuery, broadcastRange, filmingRange);
    }
  }

  function handleSearchNavigation(nextQuery: string, broadcast: DateRange, filming: DateRange) {
    if (navigationLockRef.current || isSearchPending) return;

    const href = createSearchResultsHref({ query: nextQuery, broadcast, filming });
    if (!href) return;
    if (
      typeof window !== 'undefined' &&
      isSameSearchDestination(`${window.location.pathname}${window.location.search}`, href)
    ) {
      if (api) {
        navigationLockRef.current = true;
        try {
          api.retry();
        } catch (error) {
          navigationLockRef.current = false;
          throw error;
        }
      }
      return;
    }

    navigationLockRef.current = true;
    try {
      startNavigation(() => router.push(href, { scroll: false }));
    } catch (error) {
      navigationLockRef.current = false;
      throw error;
    }
  }

  async function handleInquirySubmit(comment: string) {
    if (
      inquirySubmittingRef.current ||
      inquiryResultId === null ||
      !inquiryResult ||
      !inquiryResult.searchResultId ||
      !canCreateInquiry(searchExecution)
    ) {
      return;
    }

    const submission =
      inquirySubmission &&
      isSameInquiryRequest(inquirySubmission, inquiryResult.searchResultId, comment)
        ? inquirySubmission
        : createInquirySubmission(inquiryResult.searchResultId, comment);

    inquirySubmittingRef.current = true;
    setInquirySubmission(submission);
    setInquiryError(undefined);
    setIsInquirySubmitting(true);

    try {
      const response = await submitInquiry(submission);
      void queryClient.invalidateQueries({ queryKey: myInquiryKeys.all });
      setSubmittedInquiryIds((current) =>
        current.includes(submission.snapshot.resultId)
          ? current
          : [...current, submission.snapshot.resultId],
      );
      setInquirySuccessNotice(
        `문의 #${response.inquiryId}의 접수가 확인되었습니다. 현재 상태: ${inquiryStatusLabels[response.status]}. 문의 접수 자체로 검색 결과는 변경되지 않습니다.`,
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

  function handleInquiryOpen(resultId: number) {
    const result = results.find(({ id }) => id === resultId);
    if (
      !result?.searchResultId ||
      !canCreateInquiry(searchExecution) ||
      submittedInquiryIds.includes(result.searchResultId)
    )
      return;
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
    if (selectedResult) handleInquiryOpen(selectedResult.id);
  }

  return (
    <SearchLayout
      className={styles.shell}
      theme={theme}
      isResults
      broadcastRange={broadcastRange}
      filmingRange={filmingRange}
      isDisabled={isSearchPending}
      onBroadcastChange={(range) => handleSearchNavigation(submittedQuery, range, filmingRange)}
      onFilmingChange={(range) => handleSearchNavigation(submittedQuery, broadcastRange, range)}
      searchField={
        <form className={styles.searchForm} onSubmit={handleSearch}>
          <div className={styles.searchField} ref={searchFieldRef}>
            <input
              aria-label="뉴스 장면 검색어"
              disabled={isSearchPending}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="예: 2025년 추석 경부고속도로 귀성길 정체"
              value={query}
            />
            <button
              aria-label={isSearchPending ? '검색 중' : '검색'}
              className={styles.searchButton}
              disabled={!query.trim() || isSearchPending}
              type="submit"
            >
              <ArrowRight aria-hidden="true" />
            </button>
          </div>
        </form>
      }
    >
      <div className={styles.workspace} data-state={resultState} ref={workspaceRef}>
        <main className={styles.mainContent}>
          <h1 className={styles.visuallyHidden}>뉴스 장면 검색 결과</h1>
          <section className={styles.resolution} aria-label="검색 요약">
            <div className={styles.resolutionIcon}>
              <Sparkles aria-hidden="true" />
            </div>
            <div>
              <span>검색어</span>
              <strong>{submittedQuery}</strong>
              <p className={styles.resolutionStatus} role="status">
                검색 해석: {resolutionStatusLabel}
              </p>
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
              {!api && (
                <div className={styles.resultsMeta}>
                  <span>화면 미리보기 · 예시 데이터</span>
                </div>
              )}
            </div>

            {api?.error ? <SearchErrorToast error={api.error} /> : null}
            {resultState === 'empty' || resultState === 'populated' ? (
              <SearchResultNotices execution={searchExecution} variant="results" />
            ) : null}
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
                details={details}
                reason={api?.failureReason}
                onReset={() =>
                  handleSearchNavigation(submittedQuery, emptyDateRange, emptyDateRange)
                }
                onRetry={
                  api?.retry ??
                  (() => handleSearchNavigation(submittedQuery, broadcastRange, filmingRange))
                }
              />
            ) : (
              <>
                <div className={styles.resultsGrid}>
                  {displayedResults.map((result, index) => (
                    <SearchResultCard
                      result={result}
                      position={index + 1}
                      isSelected={isPreviewOpen && selectedResultId === result.id}
                      key={result.id}
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

      {isPreviewOpen && selectedResult ? (
        <ScenePreviewDialog
          result={selectedResult}
          theme={theme}
          isSubmitted={submittedInquiryIds.includes(selectedResult.searchResultId ?? '')}
          isSubmitting={isInquirySubmitting && inquiryResultId === selectedResult.id}
          searchExecution={searchExecution}
          onInquiry={handlePreviewInquiry}
          onClose={handlePreviewClose}
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
    </SearchLayout>
  );
}
