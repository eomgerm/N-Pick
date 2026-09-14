'use client';

import { ArrowRight, Film, Inbox, Plus } from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { inquiryStatusLabels, type InquiryStatus } from '@/features/wireframes/inquiry-state';
import type { ReviewInquiryList } from '@/features/wireframes/review-inquiry-api';
import {
  displayClipTitle,
  formatInquiryDate,
  formatInquiryTimecode,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import boardStyles from '@/features/wireframes/reviewer-board.module.css';

const filters: Array<{ value: 'all' | InquiryStatus; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'open', label: '접수' },
  { value: 'reviewing', label: '검수 중' },
  { value: 'closed', label: '종료' },
];

interface InquiryListProps {
  data: ReviewInquiryList;
  currentStatus?: InquiryStatus;
}

export function InquiryList({ data, currentStatus }: InquiryListProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const listHeadingRef = useRef<HTMLHeadingElement>(null);
  const counts = {
    all: data.statusCounts.open + data.statusCounts.reviewing + data.statusCounts.closed,
    ...data.statusCounts,
  };
  const currentPage = data.page + 1;

  useEffect(() => {
    listHeadingRef.current?.focus({ preventScroll: true });
  }, [currentStatus, data.page]);

  function move(updates: Record<string, string | null>) {
    router.push(getReviewUrl(pathname, searchParams.toString(), updates), { scroll: false });
  }

  return (
    <div className={boardStyles.board}>
      <section className={boardStyles.greeting} aria-labelledby="reviewer-greeting">
        <div>
          <p className={boardStyles.eyebrow}>REVIEWER WORKSPACE</p>
          <h1 id="reviewer-greeting">
            문의 검수
            <br />
            <span>
              전체 <em>{counts.all}건</em>
            </span>
          </h1>
        </div>
        <div className={boardStyles.headerActions}>
          <Link
            className={boardStyles.processingLink}
            href={getReviewTabUrl(pathname, searchParams.toString(), 'processing')}
          >
            영상 처리 현황 <ArrowRight aria-hidden="true" />
          </Link>
          <Link
            className={boardStyles.registrationLink}
            href={getReviewUrl(pathname, searchParams.toString(), {
              view: 'upload',
              inquiry: null,
            })}
          >
            <Plus aria-hidden="true" /> 영상 등록
          </Link>
        </div>
      </section>

      <section className={boardStyles.panel} aria-labelledby="inquiry-board-title">
        <div className={boardStyles.panelHeading}>
          <h2 id="inquiry-board-title" ref={listHeadingRef} tabIndex={-1}>
            문의 목록
          </h2>
          <span>최근 접수 순 · 10개씩</span>
        </div>
        <div className={boardStyles.filterRow}>
          <div aria-label="문의 상태" className={boardStyles.statusFilters} role="group">
            {filters.map(({ value, label }) => (
              <button
                aria-pressed={(currentStatus ?? 'all') === value}
                key={value}
                onClick={() => move({ status: value === 'all' ? null : value, page: null })}
                type="button"
              >
                {label} <span>{counts[value]}</span>
              </button>
            ))}
          </div>
        </div>
        <div className={boardStyles.listCaption}>
          <p aria-live="polite" role="status">
            총 <strong>{data.totalElements}</strong>개의 문의
          </p>
          <span>페이지 {currentPage}</span>
        </div>

        {data.items.length === 0 ? (
          <div className="grid min-h-56 place-items-center text-center text-(--muted)">
            <div>
              <Inbox aria-hidden="true" className="mx-auto mb-3" />
              <p>이 상태의 문의가 없습니다.</p>
            </div>
          </div>
        ) : (
          <ul aria-label="문의 목록" className={boardStyles.list}>
            {data.items.map((item) => (
              <li key={item.feedbackId}>
                <button
                  className={boardStyles.inquiryRow}
                  onClick={() => move({ inquiry: item.feedbackId })}
                  type="button"
                >
                  <span aria-hidden="true" className={boardStyles.thumbnail}>
                    <Film />
                    <small>
                      {formatInquiryTimecode(item.scene.startTimeMs)}–
                      {formatInquiryTimecode(item.scene.endTimeMs)}
                    </small>
                  </span>
                  <span className={boardStyles.rowCopy}>
                    <span className={boardStyles.rowMeta}>
                      문의 #{item.feedbackId}
                      <i aria-hidden="true" />
                      {item.hasComment ? '설명 있음' : '설명 없음'}
                      <span className={boardStyles.statusChip} data-status={item.status}>
                        {inquiryStatusLabels[item.status]}
                      </span>
                    </span>
                    <strong>{displayClipTitle(item.scene.clipTitle)}</strong>
                    <span>
                      {formatInquiryTimecode(item.scene.startTimeMs)}–
                      {formatInquiryTimecode(item.scene.endTimeMs)}
                    </span>
                    <span className={boardStyles.comment}>{item.queryText}</span>
                  </span>
                  <span className={boardStyles.age}>{formatInquiryDate(item.createdAt)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <nav aria-label="문의 목록 페이지" className={boardStyles.pagination}>
          <button
            disabled={currentPage <= 1}
            onClick={() => move({ page: currentPage === 2 ? null : String(currentPage - 1) })}
            type="button"
          >
            이전
          </button>
          <span>
            {currentPage} / {Math.max(data.totalPages, 1)}
          </span>
          <button
            disabled={data.totalPages === 0 || currentPage >= data.totalPages}
            onClick={() => move({ page: String(currentPage + 1) })}
            type="button"
          >
            다음
          </button>
        </nav>
      </section>
    </div>
  );
}
