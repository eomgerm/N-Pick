'use client';

import { useQuery } from '@tanstack/react-query';
import { Inbox, RefreshCw } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import { getReviewInquiries } from '@/features/wireframes/review-inquiry-api';
import { InquiryDetail } from '@/features/wireframes/review-inquiry-detail';
import { InquiryList, InquiryListHeading } from '@/features/wireframes/review-inquiry-list';
import dashboardStyles from '@/features/wireframes/review-dashboard.module.css';
import {
  selectInquiryPage,
  selectInquiryStatus,
  normalizeInquiryPage,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ReviewerLayout } from '@/features/wireframes/reviewer-layout';
import styles from '@/features/wireframes/reviewer.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';

export function ReviewInquiryWorkspace({ theme }: { theme: WireframeTheme }) {
  const member = useMember();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const status = selectInquiryStatus(searchParams.get('status'));
  const page = selectInquiryPage(searchParams.get('page'));
  const feedbackId = searchParams.get('inquiry');
  const list = useQuery({
    queryKey: ['review-inquiries', status ?? 'all', page],
    queryFn: ({ signal }) => getReviewInquiries(page - 1, status, signal),
    enabled: feedbackId === null,
  });

  useEffect(() => {
    if (feedbackId !== null || !list.data) return;
    const normalizedPage = normalizeInquiryPage(page, list.data.totalPages);
    if (normalizedPage === page) return;
    router.replace(
      getReviewUrl(pathname, searchParams.toString(), {
        page: normalizedPage === 1 ? null : String(normalizedPage),
      }),
      { scroll: false },
    );
  }, [feedbackId, list.data, page, pathname, router, searchParams]);

  return (
    <ReviewerLayout
      theme={theme}
      headerContent={feedbackId === null ? <InquiryListHeading data={list.data} /> : undefined}
    >
      <main className={styles.page}>
        {feedbackId ? (
          <InquiryDetail feedbackId={feedbackId} theme={theme} />
        ) : list.isPending ? (
          <div
            className={`${dashboardStyles.dashboard} ${dashboardStyles.statePanel}`}
            aria-busy="true"
            role="status"
          >
            <Inbox aria-hidden="true" />
            <h2>문의 목록을 불러오는 중…</h2>
            <p>접수 상태와 장면 정보를 확인하고 있어요.</p>
          </div>
        ) : list.isError ? (
          <div className={`${dashboardStyles.dashboard} ${dashboardStyles.statePanel}`}>
            <Inbox aria-hidden="true" />
            <h2>문의 목록을 불러오지 못했어요</h2>
            <p>
              {status ? `${inquiryStatusLabels[status]} 상태 · ` : '전체 상태 · '}페이지 {page}
            </p>
            <ApiErrorNotice error={list.error} />
            <button
              className={dashboardStyles.primaryButton}
              disabled={list.isFetching}
              onClick={() => list.refetch()}
              type="button"
            >
              <RefreshCw aria-hidden="true" /> {list.isFetching ? '다시 불러오는 중…' : '다시 시도'}
            </button>
          </div>
        ) : (
          <>
            <p className="sr-only">현재 검수자 {member.loginId}</p>
            <InquiryList currentStatus={status} data={list.data} />
          </>
        )}
      </main>
    </ReviewerLayout>
  );
}
