'use client';

import { useQuery } from '@tanstack/react-query';
import {
  ArrowRight,
  ArrowUpRight,
  Film,
  Inbox,
  MessageSquareText,
  Plus,
  RefreshCw,
  UploadCloud,
} from 'lucide-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClips, type ClipRunStatus } from '@/features/wireframes/clip-processing-api';
import {
  clipFilterUpdates,
  clipRunLabels,
  processingStageLabel,
  type ClipFilter,
} from '@/features/wireframes/clip-processing-view';
import { inquiryStatusLabels, type InquiryStatus } from '@/features/wireframes/inquiry-state';
import { getReviewInquiries } from '@/features/wireframes/review-inquiry-api';
import { displayClipTitle, formatInquiryDate } from '@/features/wireframes/review-inquiry-view';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ReviewerLayout } from '@/features/wireframes/reviewer-layout';
import dashboardStyles from '@/features/wireframes/review-dashboard.module.css';
import styles from '@/features/wireframes/review-overview.module.css';
import progressStyles from '@/features/wireframes/reviewer-progress.module.css';
import reviewerStyles from '@/features/wireframes/reviewer.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';

const RECENT_COUNT = 5;

const inquiryMeta: InquiryStatus[] = ['open', 'reviewing', 'closed'];

// 칩 묶음(CLIP_FILTERS)과 같은 대응: 대기·진행은 처리 중, 실패·기록 없음은 확인 필요로 연다.
const clipMeta: Array<{ status: ClipRunStatus | 'no_run'; filter: ClipFilter }> = [
  { status: 'running', filter: 'processing' },
  { status: 'queued', filter: 'processing' },
  { status: 'failed', filter: 'attention' },
  { status: 'no_run', filter: 'attention' },
];

export function ReviewOverview({ theme }: { theme: WireframeTheme }) {
  const pathname = usePathname();
  // 문의·처리 목록의 첫 페이지와 같은 키를 써서 탭을 오갈 때 캐시를 공유한다.
  const inquiries = useQuery({
    queryKey: ['review-inquiries', 'all', 1],
    queryFn: ({ signal }) => getReviewInquiries(0, undefined, signal),
  });
  const clips = useQuery({
    queryKey: ['processing-clips', 'all', false, 1],
    queryFn: ({ signal }) => getProcessingClips(0, [], false, signal),
  });
  const inquiryCounts = inquiries.data?.statusCounts;
  const inquiryTotal = inquiryCounts
    ? inquiryCounts.open + inquiryCounts.reviewing + inquiryCounts.closed
    : undefined;
  const runCounts = clips.data?.run_counts;
  const clipTotal = runCounts
    ? Object.values(runCounts).reduce((sum, count) => sum + count, 0)
    : undefined;

  return (
    <ReviewerLayout
      theme={theme}
      headerContent={
        <section className={dashboardStyles.heading} aria-labelledby="review-overview-title">
          <div className={dashboardStyles.greetingTitle}>
            <h1 id="review-overview-title">검수 개요</h1>
          </div>
          <Link
            aria-label="영상 등록"
            className={dashboardStyles.primaryButton}
            href={getReviewUrl(pathname, '', { view: 'upload' })}
            title="영상 등록"
          >
            <Plus aria-hidden="true" /> <span>영상 등록</span>
          </Link>
        </section>
      }
    >
      <main className={reviewerStyles.page}>
        <div className={`${dashboardStyles.dashboard} ${styles.overview}`}>
          <section
            className={`${dashboardStyles.panel} ${styles.summary}`}
            aria-labelledby="overview-inquiry-title"
          >
            <div className={styles.summaryTitle}>
              <Inbox aria-hidden="true" />
              <h2 id="overview-inquiry-title">문의 처리</h2>
              <span>전체 {inquiryTotal ?? '—'}개</span>
            </div>
            <p>
              <strong>{inquiryCounts?.closed ?? '—'}</strong> {inquiryStatusLabels.closed}
            </p>
            {inquiryCounts && (
              <progress
                aria-label="전체 문의 종료 현황"
                max={inquiryTotal || 1}
                value={inquiryCounts.closed}
              />
            )}
            <div className={styles.summaryMeta}>
              {inquiryMeta.map((status) => (
                <Link href={getReviewUrl(pathname, '', { status })} key={status}>
                  {inquiryStatusLabels[status]} <b>{inquiryCounts?.[status] ?? '—'}</b>
                </Link>
              ))}
            </div>
            <SummaryState query={inquiries} name="문의 현황" />
          </section>

          <section
            className={`${dashboardStyles.panel} ${styles.summary}`}
            aria-labelledby="overview-clip-title"
          >
            <div className={styles.summaryTitle}>
              <UploadCloud aria-hidden="true" />
              <h2 id="overview-clip-title">영상 등록</h2>
              <span>전체 {clipTotal ?? '—'}개</span>
            </div>
            <p>
              <strong>{runCounts?.succeeded ?? '—'}</strong> {clipRunLabels.succeeded}
            </p>
            {runCounts && (
              <progress
                aria-label="전체 영상 처리 완료 현황"
                max={clipTotal || 1}
                value={runCounts.succeeded}
              />
            )}
            <div className={styles.summaryMeta}>
              {clipMeta.map(({ status, filter }) => (
                <Link
                  href={getReviewUrl(pathname, '', {
                    view: 'processing',
                    ...clipFilterUpdates(filter),
                  })}
                  key={status}
                >
                  {clipRunLabels[status]} <b>{runCounts?.[status] ?? '—'}</b>
                </Link>
              ))}
            </div>
            <SummaryState query={clips} name="영상 현황" />
          </section>

          <section
            className={`${dashboardStyles.panel} ${styles.recent}`}
            aria-labelledby="overview-recent-inquiry-title"
          >
            <div className={dashboardStyles.panelHeading}>
              <h2 id="overview-recent-inquiry-title">최근 문의</h2>
              <span>최근 접수 순</span>
            </div>
            <PanelState query={inquiries} loadingText="최근 문의를 불러오는 중…">
              {inquiries.data &&
                (inquiries.data.items.length === 0 ? (
                  <p className={styles.empty}>접수된 문의가 없습니다.</p>
                ) : (
                  <ul aria-label="최근 문의" className={dashboardStyles.list}>
                    {inquiries.data.items.slice(0, RECENT_COUNT).map((item) => (
                      <li key={item.feedbackId}>
                        <Link
                          className={dashboardStyles.inquiryRow}
                          href={getReviewUrl(pathname, '', { inquiry: item.feedbackId })}
                        >
                          <span aria-hidden="true" className={dashboardStyles.thumbnail}>
                            <MessageSquareText />
                          </span>
                          <span className={dashboardStyles.rowCopy}>
                            <span className={`${dashboardStyles.rowMeta} ${styles.line}`}>
                              문의 #{item.feedbackId}
                            </span>
                            <strong className={styles.line} title={item.queryText}>
                              <span className={dashboardStyles.queryLabel}>검색어</span>
                              {item.queryText}
                            </strong>
                            {/* 좁은 행에서 타임코드까지 두면 영상 제목이 거의 잘린다. 구간은 상세에서 본다. */}
                            <span
                              className={`${dashboardStyles.sceneMeta} ${styles.line}`}
                              title={displayClipTitle(item.scene.clipTitle)}
                            >
                              <span>{displayClipTitle(item.scene.clipTitle)}</span>
                            </span>
                          </span>
                          <span className={dashboardStyles.rowEnd}>
                            <span className={dashboardStyles.statusChip} data-status={item.status}>
                              {inquiryStatusLabels[item.status]}
                            </span>
                            <span className={dashboardStyles.date}>
                              {formatInquiryDate(item.createdAt)}
                            </span>
                            <ArrowUpRight aria-hidden="true" />
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                ))}
            </PanelState>
            <Link className={styles.footerLink} href={getReviewTabUrl(pathname, '', 'inquiries')}>
              문의 목록 보기 <ArrowRight aria-hidden="true" />
            </Link>
          </section>

          <section
            className={`${dashboardStyles.panel} ${styles.recent}`}
            aria-labelledby="overview-recent-clip-title"
          >
            <div className={dashboardStyles.panelHeading}>
              <h2 id="overview-recent-clip-title">최근 등록 영상</h2>
              <span>최근 등록 순</span>
            </div>
            <PanelState query={clips} loadingText="최근 등록 영상을 불러오는 중…">
              {clips.data &&
                (clips.data.items.length === 0 ? (
                  <p className={styles.empty}>등록된 영상이 없습니다.</p>
                ) : (
                  <ul aria-label="최근 등록 영상" className={dashboardStyles.list}>
                    {clips.data.items.slice(0, RECENT_COUNT).map((video) => (
                      <li key={video.clip_id}>
                        <Link
                          className={dashboardStyles.inquiryRow}
                          href={getReviewUrl(pathname, '', {
                            view: 'processing',
                            clip: video.clip_id,
                          })}
                        >
                          <span aria-hidden="true" className={dashboardStyles.thumbnail}>
                            <Film />
                          </span>
                          <span className={dashboardStyles.rowCopy}>
                            <span className={`${dashboardStyles.rowMeta} ${styles.line}`}>
                              {video.source_type === 'broadcast' ? '방송 영상' : '자료 영상'}
                              {video.registered_by && ` · 등록자 ${video.registered_by.login_id}`}
                            </span>
                            <strong className={styles.line} title={displayClipTitle(video.title)}>
                              {displayClipTitle(video.title)}
                            </strong>
                            <span className={`${dashboardStyles.sceneMeta} ${styles.line}`}>
                              <span>
                                {video.progress?.current_stage
                                  ? processingStageLabel(video.progress.current_stage)
                                  : '진행 단계 미확인'}
                              </span>
                              <span>{video.search_available ? '검색 가능' : '검색 미제공'}</span>
                            </span>
                          </span>
                          <span className={dashboardStyles.rowEnd}>
                            <span
                              className={progressStyles.chip}
                              data-status={video.latest_run?.status}
                            >
                              {clipRunLabels[video.latest_run?.status ?? 'no_run']}
                            </span>
                            <span className={dashboardStyles.date}>
                              {formatInquiryDate(video.created_at)}
                            </span>
                            <ArrowUpRight aria-hidden="true" />
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                ))}
            </PanelState>
            <Link className={styles.footerLink} href={getReviewTabUrl(pathname, '', 'processing')}>
              영상 목록 보기 <ArrowRight aria-hidden="true" />
            </Link>
          </section>
        </div>
      </main>
    </ReviewerLayout>
  );
}

interface PanelStateProps {
  query: {
    isPending: boolean;
    isError: boolean;
    isFetching: boolean;
    error: unknown;
    refetch: () => unknown;
  };
  loadingText: string;
  children: ReactNode;
}

function PanelState({ query, loadingText, children }: PanelStateProps) {
  if (query.isPending) {
    return (
      <p aria-busy="true" className={styles.empty} role="status">
        {loadingText}
      </p>
    );
  }
  if (query.isError) {
    return (
      <div className={styles.error}>
        <ApiErrorNotice error={query.error} />
        <button
          className={dashboardStyles.primaryButton}
          disabled={query.isFetching}
          onClick={() => query.refetch()}
          type="button"
        >
          <RefreshCw aria-hidden="true" /> {query.isFetching ? '다시 불러오는 중…' : '다시 시도'}
        </button>
      </div>
    );
  }
  return children;
}

function SummaryState({ query, name }: { query: PanelStateProps['query']; name: string }) {
  if (query.isPending) return <p role="status">{name}을 불러오는 중…</p>;
  if (!query.isError) return null;
  return (
    <div className={styles.error}>
      <ApiErrorNotice error={query.error} />
      <button
        className={dashboardStyles.primaryButton}
        disabled={query.isFetching}
        onClick={() => query.refetch()}
        type="button"
      >
        <RefreshCw aria-hidden="true" /> {name} 다시 시도
      </button>
    </div>
  );
}
