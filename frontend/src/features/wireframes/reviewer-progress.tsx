'use client';

import { useQuery } from '@tanstack/react-query';
import { ArrowRight, CheckCircle2, Film, Plus, RefreshCw } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClips } from '@/features/wireframes/clip-processing-api';
import {
  CLIP_FILTERS,
  clipFilterCounts,
  clipFilterStatuses,
  clipFilterUpdates,
  clipListPollInterval,
  clipRunLabels,
  processingProgressLabel,
  processingStageLabel,
  selectClipFilter,
  type ClipFilter,
} from '@/features/wireframes/clip-processing-view';
import {
  ProcessingRefreshStatus,
  useProcessingRefreshState,
} from '@/features/wireframes/processing-refresh-status';
import dashboardStyles from '@/features/wireframes/review-dashboard.module.css';
import {
  displayClipTitle,
  formatInquiryDate,
  selectInquiryPage,
  normalizeInquiryPage,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import styles from '@/features/wireframes/reviewer-progress.module.css';

function emptyHeading(filter: ClipFilter, mine: boolean) {
  if (filter === 'all') {
    return mine ? '내가 등록한 영상이 없습니다.' : '등록된 영상이 없습니다.';
  }
  return mine ? '내가 등록한 영상 중 이 상태인 영상이 없습니다.' : '이 상태인 영상이 없습니다.';
}

interface ReviewerProgressProps {
  isNavigating: boolean;
  onFilterChange: (updates: Record<string, string | null>) => void;
  onVideoSelect: (id: string) => void;
  onPageChange: (page: number) => void;
}
interface ReviewerProgressHeadingProps {
  isNavigating: boolean;
  onRegistrationOpen: () => void;
}
export function ReviewerProgressHeading({
  isNavigating,
  onRegistrationOpen,
}: ReviewerProgressHeadingProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, []);
  return (
    <div className={dashboardStyles.heading}>
      <h1 ref={headingRef} tabIndex={-1}>
        영상 처리 현황
      </h1>
      <button
        aria-label="영상 등록"
        className={dashboardStyles.primaryButton}
        disabled={isNavigating}
        onClick={onRegistrationOpen}
        title="영상 등록"
        type="button"
      >
        <Plus aria-hidden="true" /> <span>영상 등록</span>
      </button>
    </div>
  );
}

export function ReviewerProgress({
  isNavigating,
  onFilterChange,
  onVideoSelect,
  onPageChange,
}: ReviewerProgressProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const page = selectInquiryPage(searchParams.get('progressPage'));
  const filter = selectClipFilter(searchParams.get('clipStatus'));
  const mine = searchParams.get('mine') === 'true';
  const videos = useQuery({
    queryKey: ['processing-clips', filter, mine, page],
    queryFn: ({ signal }) => getProcessingClips(page - 1, clipFilterStatuses(filter), mine, signal),
    refetchInterval: (query) =>
      clipListPollInterval(query.state.data?.run_counts, query.state.status === 'error'),
  });
  const refreshState = useProcessingRefreshState({
    canPoll: Boolean(clipListPollInterval(videos.data?.run_counts, videos.isError)),
    hasError: videos.isError,
    fetchStatus: videos.fetchStatus,
  });
  const counts = videos.data ? clipFilterCounts(videos.data.run_counts) : undefined;
  const totalPages = videos.data?.total_pages;
  const total = videos.data?.total_elements;
  const filterRefs = useRef<Array<HTMLButtonElement | null>>([]);

  useEffect(() => {
    if (totalPages === undefined || videos.isError) return;
    const normalizedPage = normalizeInquiryPage(page, totalPages);
    if (normalizedPage === page) return;
    router.replace(
      getReviewUrl(pathname, searchParams.toString(), {
        progressPage: normalizedPage === 1 ? null : String(normalizedPage),
      }),
      { scroll: false },
    );
  }, [videos.isError, page, pathname, router, searchParams, totalPages]);

  function handleFilterKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, index: number) {
    if (isNavigating) return;
    let next: number;
    if (event.key === 'ArrowRight') next = (index + 1) % CLIP_FILTERS.length;
    else if (event.key === 'ArrowLeft')
      next = (index - 1 + CLIP_FILTERS.length) % CLIP_FILTERS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = CLIP_FILTERS.length - 1;
    else return;
    event.preventDefault();
    // 포커스만 옮긴다. 여기서 선택까지 하면 키를 누른 수만큼 히스토리가 쌓여 뒤로가기가 칩을 되짚는다.
    // 선택은 Enter·Space 가 버튼의 onClick 으로 처리한다.
    filterRefs.current[next]?.focus();
  }

  return (
    <div className={`${dashboardStyles.dashboard} ${styles.page}`}>
      <section aria-label="영상 목록" className={dashboardStyles.panel}>
        <div className={dashboardStyles.panelHeading}>
          <div>
            <h2>영상 목록</h2>
            <p>처리 상태와 검색 제공 여부를 확인하세요.</p>
          </div>
          <button
            className={styles.detailButton}
            type="button"
            disabled={isNavigating || videos.isFetching}
            onClick={() => void videos.refetch()}
          >
            <RefreshCw aria-hidden="true" /> 현황 새로고침
          </button>
        </div>

        <ProcessingRefreshStatus state={refreshState} dataUpdatedAt={videos.dataUpdatedAt} />

        {videos.isError && (
          <div className={styles.listError}>
            <ApiErrorNotice error={videos.error} />
            <button
              type="button"
              className={styles.detailButton}
              disabled={videos.isFetching}
              onClick={() => void videos.refetch()}
            >
              영상 현황 다시 시도
            </button>
          </div>
        )}

        <div className={dashboardStyles.filterRow}>
          <div aria-label="처리 상태" className={dashboardStyles.statusFilters} role="group">
            {CLIP_FILTERS.map(({ value, label }, index) => (
              <button
                key={value}
                ref={(node) => {
                  filterRefs.current[index] = node;
                }}
                type="button"
                aria-pressed={filter === value}
                aria-disabled={isNavigating}
                onKeyDown={(event) => handleFilterKeyDown(event, index)}
                onClick={() => {
                  if (!isNavigating) onFilterChange(clipFilterUpdates(value));
                }}
              >
                {label} <span>{counts?.[value] ?? '—'}</span>
              </button>
            ))}
          </div>
          <label className={styles.mineToggle}>
            <input
              type="checkbox"
              checked={mine}
              disabled={isNavigating}
              onChange={(event) =>
                onFilterChange({ mine: event.target.checked ? 'true' : null, progressPage: null })
              }
            />
            내 영상만 보기
          </label>
        </div>

        <div className={dashboardStyles.listCaption}>
          <p aria-live="polite" role="status">
            총 <strong>{total ?? '—'}</strong>건{mine && ' · 내가 등록한 영상'}
          </p>
          <span>페이지 {page} · 10개씩</span>
        </div>

        {videos.isError ? (
          <p className={styles.empty} role="status">
            최신 목록을 불러오지 못했습니다. 위 안내에서 다시 시도해 주세요.
          </p>
        ) : videos.isPending ? (
          <p className={styles.empty} role="status">
            목록을 불러오는 중…
          </p>
        ) : total === 0 ? (
          <div className={styles.empty}>
            <CheckCircle2 aria-hidden="true" />
            <h3>{emptyHeading(filter, mine)}</h3>
            {filter !== 'all' && <p>다른 상태를 선택하면 등록된 영상을 확인할 수 있어요.</p>}
          </div>
        ) : (
          <ul className={styles.list}>
            {videos.data?.items.map((video) => (
              <li key={video.clip_id} className={styles.videoRow}>
                <span className={styles.videoIcon}>
                  <Film aria-hidden="true" />
                </span>
                <div className={styles.videoCopy}>
                  <div className={styles.videoTitle}>
                    <strong>{displayClipTitle(video.title)}</strong>
                    <span className={styles.chip} data-status={video.latest_run?.status}>
                      {clipRunLabels[video.latest_run?.status ?? 'no_run']}
                    </span>
                  </div>
                  <p className={styles.fileName}>
                    {video.source_type === 'broadcast' ? '방송 영상' : '자료 영상'} · 등록{' '}
                    {formatInquiryDate(video.created_at)}
                    {video.registered_by && ` · 등록자 ${video.registered_by.login_id}`}
                  </p>
                  <p className={styles.stageCaption}>
                    <span>
                      {video.progress?.current_stage
                        ? processingStageLabel(video.progress.current_stage)
                        : '진행 단계 미확인'}
                    </span>
                    <span className={styles.availability} data-available={video.search_available}>
                      {video.search_available ? '검색 가능' : '검색 미제공'}
                    </span>
                  </p>
                  {video.progress?.total_steps != null && video.progress.total_steps > 0 && (
                    <progress
                      aria-label={displayClipTitle(video.title) + ' 처리 단계'}
                      max={video.progress.total_steps}
                      value={
                        (video.progress.succeeded_steps ?? 0) + (video.progress.skipped_steps ?? 0)
                      }
                      data-status={video.latest_run?.status}
                    />
                  )}
                  <p className={styles.videoDescription}>
                    {processingProgressLabel(video.progress)}
                  </p>
                  {video.latest_run?.error_code && (
                    <p className={styles.errorCaption}>
                      처리를 완료하지 못했습니다. 상세 내역을 확인해 주세요.
                    </p>
                  )}
                </div>
                <button
                  className={styles.detailButton}
                  type="button"
                  disabled={isNavigating}
                  aria-label={displayClipTitle(video.title) + ' 처리 상세'}
                  onClick={() => onVideoSelect(video.clip_id)}
                >
                  상세 보기 <ArrowRight aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        )}

        {!videos.isError && (
          <nav aria-label="영상 목록 페이지" className={styles.pagination}>
            <button
              type="button"
              disabled={isNavigating || videos.isFetching || page <= 1}
              onClick={() => onPageChange(page - 1)}
            >
              이전 페이지
            </button>
            <span>
              {page} / {Math.max(totalPages ?? 1, 1)}
            </span>
            <button
              type="button"
              disabled={
                isNavigating || videos.isFetching || totalPages === undefined || page >= totalPages
              }
              onClick={() => onPageChange(page + 1)}
            >
              다음 페이지
            </button>
          </nav>
        )}
      </section>
    </div>
  );
}
