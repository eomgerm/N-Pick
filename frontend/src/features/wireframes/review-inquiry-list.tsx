'use client';

import { ArrowUpRight, Film, Inbox, Plus } from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { inquiryStatusLabels, type InquiryStatus } from '@/features/wireframes/inquiry-state';
import { DEFAULT_PAGE_SIZE } from '@/features/wireframes/list-pagination';
import {
  PageJump,
  PageNumbers,
  PageSizeSelect,
} from '@/features/wireframes/list-pagination-controls';
import type { ReviewInquiryList } from '@/features/wireframes/review-inquiry-api';
import {
  displayClipTitle,
  formatInquiryDate,
  formatInquiryTimecode,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import boardStyles from '@/features/wireframes/review-dashboard.module.css';

const filters: Array<{ value: 'all' | InquiryStatus; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'open', label: '접수' },
  { value: 'reviewing', label: '검수 중' },
  { value: 'closed', label: '종료' },
];

interface InquiryListProps {
  data: ReviewInquiryList;
  currentStatus?: InquiryStatus;
  pageSize: number;
}

interface InquiryListHeadingProps {
  data?: ReviewInquiryList;
}

export function InquiryListHeading({ data }: InquiryListHeadingProps) {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const totalCount = data
    ? data.statusCounts.open + data.statusCounts.reviewing + data.statusCounts.closed
    : undefined;

  return (
    <section className={boardStyles.heading} aria-labelledby="reviewer-greeting">
      <div className={boardStyles.greetingTitle}>
        <h1 id="reviewer-greeting">문의 검수</h1>
        <span className={boardStyles.totalCount}>
          전체 <em>{totalCount ?? '—'}건</em>
        </span>
      </div>
      <Link
        aria-label="영상 등록"
        className={boardStyles.primaryButton}
        href={getReviewUrl(pathname, searchParams.toString(), {
          view: 'upload',
          inquiry: null,
        })}
        title="영상 등록"
      >
        <Plus aria-hidden="true" /> <span>영상 등록</span>
      </Link>
    </section>
  );
}

export function InquiryList({ data, currentStatus, pageSize }: InquiryListProps) {
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

  function handlePageChange(page: number) {
    move({ page: page === 1 ? null : String(page) });
  }

  return (
    <div className={boardStyles.dashboard}>
      <section className={boardStyles.panel} aria-labelledby="inquiry-board-title">
        <div className={boardStyles.panelHeading}>
          <div>
            <h2 id="inquiry-board-title" ref={listHeadingRef} tabIndex={-1}>
              문의 목록
            </h2>
            <p>검색어와 장면을 확인하고 접수된 문의를 검수해 주세요.</p>
          </div>
          <span>최근 접수 순</span>
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
          <PageSizeSelect
            pageSize={pageSize}
            onPageSizeChange={(size) =>
              move({ size: size === DEFAULT_PAGE_SIZE ? null : String(size), page: null })
            }
          />
        </div>

        {data.items.length === 0 ? (
          <div className={boardStyles.empty}>
            <Inbox aria-hidden="true" />
            <h3>해당 상태 문의 없음</h3>
            <p>다른 상태를 선택하면 접수된 문의를 확인할 수 있어요.</p>
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
                  </span>
                  <span className={boardStyles.rowCopy}>
                    <span className={boardStyles.rowMeta}>
                      문의 #{item.feedbackId}
                      <span>{item.hasComment ? '설명 있음' : '설명 없음'}</span>
                    </span>
                    <strong>
                      <span className={boardStyles.queryLabel}>검색어</span>
                      {item.queryText}
                    </strong>
                    <span className={boardStyles.sceneMeta}>
                      <span>{displayClipTitle(item.scene.clipTitle)}</span>
                      <span className={boardStyles.timecode}>
                        {formatInquiryTimecode(item.scene.startTimeMs)}–
                        {formatInquiryTimecode(item.scene.endTimeMs)}
                      </span>
                    </span>
                  </span>
                  <span className={boardStyles.rowEnd}>
                    <span className={boardStyles.statusChip} data-status={item.status}>
                      {inquiryStatusLabels[item.status]}
                    </span>
                    <span className={boardStyles.date}>{formatInquiryDate(item.createdAt)}</span>
                    <ArrowUpRight aria-hidden="true" />
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <nav aria-label="문의 목록 페이지" className={boardStyles.pagination}>
          <PageNumbers
            page={currentPage}
            totalPages={data.totalPages}
            onPageChange={handlePageChange}
          />
          <PageJump
            // 목록 조건이 바뀌면 입력과 범위 밖 안내를 비운다.
            key={`${currentStatus ?? 'all'}|${pageSize}`}
            totalPages={data.totalPages}
            onPageChange={handlePageChange}
          />
        </nav>
      </section>
    </div>
  );
}
