'use client';

import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { AppShell } from '@/components/app-shell';
import { useMember } from '@/components/session-boundary';
import { inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import { getReviewInquiries } from '@/features/wireframes/review-inquiry-api';
import { InquiryDetail } from '@/features/wireframes/review-inquiry-detail';
import { InquiryList } from '@/features/wireframes/review-inquiry-list';
import {
  selectInquiryPage,
  selectInquiryStatus,
  normalizeInquiryPage,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
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
    <AppShell className={styles.shell} data-theme={theme}>
      <main className={styles.page}>
        <nav aria-label="검수 화면" className="mb-7 flex gap-2 border-b border-(--line)">
          <Link
            aria-current="page"
            className="border-b-3 border-(--accent) px-5 py-3 text-sm font-bold text-(--accent-strong)"
            href={getReviewTabUrl(pathname, searchParams.toString(), 'inquiries')}
          >
            문의
          </Link>
          <Link
            className="border-b-3 border-transparent px-5 py-3 text-sm font-bold text-(--muted)"
            href={getReviewTabUrl(pathname, searchParams.toString(), 'processing')}
          >
            처리
          </Link>
        </nav>

        {feedbackId ? (
          <InquiryDetail feedbackId={feedbackId} />
        ) : list.isPending ? (
          <p aria-busy="true" className="py-24 text-center" role="status">
            문의 목록을 불러오는 중…
          </p>
        ) : list.isError ? (
          <div className="mx-auto max-w-3xl py-12">
            <p className="mb-3 text-sm text-(--muted)">
              {status ? `${inquiryStatusLabels[status]} 상태 · ` : '전체 상태 · '}페이지 {page}
            </p>
            <ApiErrorNotice error={list.error} />
            <button className="mt-4 underline" onClick={() => list.refetch()} type="button">
              다시 시도
            </button>
          </div>
        ) : (
          <>
            <p className="sr-only">현재 검수자 {member.loginId}</p>
            <InquiryList currentStatus={status} data={list.data} />
          </>
        )}
      </main>
    </AppShell>
  );
}
