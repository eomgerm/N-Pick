'use client';

import { useQuery } from '@tanstack/react-query';
import { ArrowRight, CheckCircle2, Film, Inbox, Plus, RefreshCw, UploadCloud } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { type KeyboardEvent, useEffect, useRef } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClips } from '@/features/wireframes/clip-processing-api';
import {
  clipListPollInterval,
  clipRunLabels,
  processingProgressLabel,
  processingStageLabel,
} from '@/features/wireframes/clip-processing-view';
import { getReviewInquiries } from '@/features/wireframes/review-inquiry-api';
import dashboardStyles from '@/features/wireframes/review-dashboard.module.css';
import {
  displayClipTitle,
  formatInquiryDate,
  selectInquiryPage,
  normalizeInquiryPage,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import type { ProgressTab } from '@/features/wireframes/reviewer-progress-state';
import styles from '@/features/wireframes/reviewer-progress.module.css';

interface ReviewerProgressProps {
  activeTab: ProgressTab;
  isNavigating: boolean;
  onTabChange: (tab: ProgressTab) => void;
  onInquirySelect: (id: string) => void;
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
  activeTab,
  isNavigating,
  onTabChange,
  onInquirySelect,
  onVideoSelect,
  onPageChange,
}: ReviewerProgressProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const page = selectInquiryPage(searchParams.get('progressPage'));
  const isInquiries = activeTab === 'inquiries';
  const inquiryPage = isInquiries ? page : 1;
  const videoPage = isInquiries ? 1 : page;
  const isCompleted = activeTab === 'completed';
  const inquiries = useQuery({
    queryKey: ['review-inquiries', 'reviewing', inquiryPage],
    queryFn: ({ signal }) => getReviewInquiries(inquiryPage - 1, 'reviewing', signal),
    refetchInterval: (query) =>
      query.state.status !== 'error' && (query.state.data?.statusCounts.reviewing ?? 0) > 0
        ? 15_000
        : false,
  });
  const videos = useQuery({
    queryKey: ['processing-clips', isCompleted ? 'completed' : 'active', videoPage],
    queryFn: ({ signal }) => getProcessingClips(videoPage - 1, isCompleted, signal),
    refetchInterval: (query) =>
      clipListPollInterval(query.state.data?.run_counts, query.state.status === 'error'),
  });
  const inquiryCounts = inquiries.data?.statusCounts;
  const videoCounts = videos.data?.run_counts;
  const inquiryTotal = inquiryCounts
    ? inquiryCounts.open + inquiryCounts.reviewing + inquiryCounts.closed
    : undefined;
  const videoTotal = videoCounts
    ? Object.values(videoCounts).reduce((sum, count) => sum + count, 0)
    : undefined;
  const activeVideoTotal = videoCounts
    ? videoCounts.queued + videoCounts.running + videoCounts.failed + videoCounts.no_run
    : undefined;
  const currentQuery = isInquiries ? inquiries : videos;
  const totalPages = isInquiries ? inquiries.data?.totalPages : videos.data?.total_pages;
  const total = isInquiries ? inquiries.data?.totalElements : videos.data?.total_elements;
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const tabs = [
    { id: 'inquiries', label: '문의 처리 중', count: inquiryCounts?.reviewing, icon: Inbox },
    { id: 'uploads', label: '영상 등록 중', count: activeVideoTotal, icon: UploadCloud },
    { id: 'completed', label: '등록 완료', count: videoCounts?.succeeded, icon: CheckCircle2 },
  ] as const;

  useEffect(() => {
    if (totalPages === undefined || currentQuery.isError) return;
    const normalizedPage = normalizeInquiryPage(page, totalPages);
    if (normalizedPage === page) return;
    router.replace(
      getReviewUrl(pathname, searchParams.toString(), {
        progressPage: normalizedPage === 1 ? null : String(normalizedPage),
      }),
      { scroll: false },
    );
  }, [currentQuery.isError, page, pathname, router, searchParams, totalPages]);

  function handleTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (isNavigating) return;
    let next: number;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    tabRefs.current[next]?.focus();
    onTabChange(tabs[next].id);
  }

  return (
    <div className={`${dashboardStyles.dashboard} ${styles.page}`}>
      <div className={styles.summary}>
        <section aria-label="문의 처리 요약">
          <div className={styles.summaryTitle}>
            <Inbox aria-hidden="true" />
            <h2>문의 처리</h2>
            <span>전체 {inquiryTotal ?? '—'}개</span>
          </div>
          <p>
            <strong>{inquiryCounts?.closed ?? '—'}</strong> 종료
          </p>
          {inquiryCounts && (
            <progress
              aria-label="전체 문의 종료 현황"
              max={inquiryTotal || 1}
              value={inquiryCounts.closed}
            />
          )}
          <div className={styles.summaryMeta}>
            <span>
              처리 중 <b>{inquiryCounts?.reviewing ?? '—'}</b>
            </span>
            <span>
              접수 <b>{inquiryCounts?.open ?? '—'}</b>
            </span>
            <span>
              종료 <b>{inquiryCounts?.closed ?? '—'}</b>
            </span>
          </div>
          {inquiries.isPending && <p role="status">문의 현황을 불러오는 중…</p>}
          {inquiries.isError && (
            <div className="mt-4">
              <ApiErrorNotice error={inquiries.error} />
              <button
                type="button"
                className={styles.detailButton}
                disabled={inquiries.isFetching}
                onClick={() => inquiries.refetch()}
              >
                문의 현황 다시 시도
              </button>
            </div>
          )}
        </section>
        <section aria-label="영상 등록 요약">
          <div className={styles.summaryTitle}>
            <UploadCloud aria-hidden="true" />
            <h2>영상 등록</h2>
            <span>전체 {videoTotal ?? '—'}개</span>
          </div>
          <p>
            <strong>{videoCounts?.succeeded ?? '—'}</strong> 처리 완료
          </p>
          {videoCounts && (
            <progress
              aria-label="전체 영상 처리 완료 현황"
              max={videoTotal || 1}
              value={videoCounts.succeeded}
            />
          )}
          <div className={styles.summaryMeta}>
            <span>
              진행 중 <b>{videoCounts?.running ?? '—'}</b>
            </span>
            <span>
              대기 <b>{videoCounts?.queued ?? '—'}</b>
            </span>
            <span>
              확인 필요 <b>{videoCounts?.failed ?? '—'}</b>
            </span>
            <span>
              기록 없음 <b>{videoCounts?.no_run ?? '—'}</b>
            </span>
          </div>
          {videos.isPending && <p role="status">영상 현황을 불러오는 중…</p>}
          {videos.isError && (
            <div className="mt-4">
              <ApiErrorNotice error={videos.error} />
              <button
                type="button"
                className={styles.detailButton}
                disabled={videos.isFetching}
                onClick={() => videos.refetch()}
              >
                영상 현황 다시 시도
              </button>
            </div>
          )}
        </section>
      </div>
      <section aria-label="처리 목록" className={styles.panel}>
        <div aria-label="처리 유형" className={styles.tabs} role="tablist">
          {tabs.map(({ id, label, count, icon: Icon }, index) => (
            <button
              key={id}
              ref={(node) => {
                tabRefs.current[index] = node;
              }}
              id={'progress-tab-' + id}
              type="button"
              role="tab"
              aria-selected={activeTab === id}
              aria-controls={'progress-panel-' + id}
              tabIndex={activeTab === id ? 0 : -1}
              aria-disabled={isNavigating}
              onKeyDown={(event) => handleTabKeyDown(event, index)}
              onClick={() => {
                if (!isNavigating) onTabChange(id);
              }}
            >
              <Icon aria-hidden="true" />
              {label}
              <span>{count ?? '—'}</span>
            </button>
          ))}
        </div>
        <div
          role="tabpanel"
          id={'progress-panel-' + activeTab}
          aria-labelledby={'progress-tab-' + activeTab}
          aria-busy={currentQuery.isFetching}
        >
          <div className={styles.listHeading}>
            <div>
              <h2>
                {isInquiries
                  ? '검수 중인 문의'
                  : isCompleted
                    ? '처리가 완료된 영상'
                    : '처리 중이거나 확인이 필요한 영상'}
              </h2>
              <p>
                전체 {total ?? '—'}건{!isInquiries && ' · 처리 상태와 검색 제공 여부를 확인하세요.'}
              </p>
            </div>
            <button
              className={styles.detailButton}
              type="button"
              disabled={isNavigating || inquiries.isFetching || videos.isFetching}
              onClick={() => {
                void inquiries.refetch();
                void videos.refetch();
              }}
            >
              <RefreshCw aria-hidden="true" /> 현황 새로고침
            </button>
          </div>
          {currentQuery.isError ? (
            <p className={styles.empty} role="status">
              최신 목록을 불러오지 못했습니다. 위 안내에서 다시 시도해 주세요.
            </p>
          ) : currentQuery.isPending ? (
            <p className={styles.empty} role="status">
              목록을 불러오는 중…
            </p>
          ) : total === 0 ? (
            <div className={styles.empty}>
              <CheckCircle2 aria-hidden="true" />
              <h3>{isInquiries ? '처리 중인 문의가 없습니다.' : '해당 상태의 영상이 없습니다.'}</h3>
            </div>
          ) : isInquiries ? (
            <ul className={styles.list}>
              {inquiries.data?.items.map((inquiry) => (
                <li key={inquiry.feedbackId}>
                  <button
                    className={styles.inquiryRow}
                    type="button"
                    disabled={isNavigating}
                    aria-label={
                      '문의 #' +
                      inquiry.feedbackId +
                      ' ' +
                      displayClipTitle(inquiry.scene.clipTitle)
                    }
                    onClick={() => onInquirySelect(inquiry.feedbackId)}
                  >
                    <span className={styles.thumbnail}>
                      <Film aria-hidden="true" />
                    </span>
                    <span className={styles.rowCopy}>
                      <span className={styles.rowMeta}>
                        <span>문의 #{inquiry.feedbackId}</span>
                        <span>{formatInquiryDate(inquiry.createdAt)}</span>
                        {inquiry.hasComment && <span>의견 있음</span>}
                      </span>
                      <strong>
                        <span className={dashboardStyles.queryLabel}>검색어</span>
                        {inquiry.queryText}
                      </strong>
                      <span className={styles.description}>
                        {displayClipTitle(inquiry.scene.clipTitle)}
                      </span>
                    </span>
                    <span className={styles.rowEnd}>
                      <span className={dashboardStyles.statusChip} data-status="reviewing">
                        검수 중
                      </span>
                      <ArrowRight aria-hidden="true" />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
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
                      {video.source_type === 'broadcast' ? '방송 영상' : '보관 영상'} · 등록{' '}
                      {formatInquiryDate(video.created_at)}
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
                          (video.progress.succeeded_steps ?? 0) +
                          (video.progress.skipped_steps ?? 0)
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
          {totalPages !== undefined && totalPages > 1 && !currentQuery.isError && (
            <nav aria-label="처리 목록 페이지" className={styles.pagination}>
              <button
                type="button"
                disabled={isNavigating || currentQuery.isFetching || page <= 1}
                onClick={() => onPageChange(page - 1)}
              >
                이전 페이지
              </button>
              <span>
                {page} / {totalPages}
              </span>
              <button
                type="button"
                disabled={isNavigating || currentQuery.isFetching || page >= totalPages}
                onClick={() => onPageChange(page + 1)}
              >
                다음 페이지
              </button>
            </nav>
          )}
        </div>
      </section>
    </div>
  );
}
