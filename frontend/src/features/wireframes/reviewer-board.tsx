'use client';

import {
  ArrowDownWideNarrow,
  ArrowRight,
  Building2,
  ChevronLeft,
  ChevronRight,
  CloudRain,
  Inbox,
  Plus,
  Search,
  SlidersHorizontal,
} from 'lucide-react';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useRef } from 'react';

import {
  getBoardStatus,
  selectBoardPage,
  type BoardStatus,
  type ReviewBoardItem,
} from '@/features/wireframes/reviewer-board-state';
import styles from '@/features/wireframes/reviewer-board.module.css';

interface ReviewerBoardProps {
  items: ReviewBoardItem[];
  isNavigating: boolean;
  onInquirySelect: (id: string) => void;
  onProcessingOpen: () => void;
  onRegistrationOpen: () => void;
  onLocationChange: (updates: Record<string, string | null>) => void;
}

const filters: { value: BoardStatus; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'pending', label: '대기' },
  { value: 'reviewing', label: '처리중' },
  { value: 'completed', label: '완료' },
];

const statusLabels = { pending: '대기', reviewing: '처리중', completed: '완료' };

export function ReviewerBoard({
  items,
  isNavigating,
  onInquirySelect,
  onProcessingOpen,
  onRegistrationOpen,
  onLocationChange,
}: ReviewerBoardProps) {
  const searchParams = useSearchParams();
  const board = selectBoardPage(items, new URLSearchParams(searchParams.toString()));
  const listHeadingRef = useRef<HTMLHeadingElement>(null);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isNavigating) return;
    const query = String(new FormData(event.currentTarget).get('q') ?? '').trim();
    onLocationChange({ q: query, page: null });
  }

  function handlePageChange(page: number) {
    onLocationChange({ page: page === 1 ? null : String(page) });
    listHeadingRef.current?.focus({ preventScroll: true });
    listHeadingRef.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
  }

  return (
    <div className={styles.board}>
      <section className={styles.greeting} aria-labelledby="reviewer-greeting">
        <div>
          <p className={styles.eyebrow}>REVIEWER WORKSPACE</p>
          <h1 id="reviewer-greeting">
            안녕하세요 나현우님.
            <br />
            <span>
              문의 내역이 <em>{items.length}개</em> 있어요
            </span>
          </h1>
        </div>
        <div className={styles.headerActions}>
          <button
            className={styles.processingLink}
            disabled={isNavigating}
            onClick={onProcessingOpen}
            type="button"
          >
            영상 처리 현황
            <ArrowRight aria-hidden="true" />
          </button>
          <button
            className={styles.registrationLink}
            disabled={isNavigating}
            onClick={onRegistrationOpen}
            type="button"
          >
            <Plus aria-hidden="true" /> 영상 등록
          </button>
        </div>
      </section>

      <section
        className={styles.panel}
        aria-busy={isNavigating}
        aria-labelledby="inquiry-board-title"
      >
        <div className={styles.panelHeading}>
          <h2 id="inquiry-board-title" ref={listHeadingRef} tabIndex={-1}>
            처리할 영상들 목록
          </h2>
          <span>문의 내용을 확인하고 검수를 시작해 주세요.</span>
        </div>
        <form
          aria-label="문의 내역 검색"
          className={styles.searchForm}
          onSubmit={handleSearch}
          role="search"
        >
          <Search aria-hidden="true" />
          <label className={styles.srOnly} htmlFor="inquiry-search">
            문의 내역 검색어
          </label>
          <input
            autoComplete="off"
            defaultValue={searchParams.get('q') ?? ''}
            id="inquiry-search"
            key={searchParams.get('q') ?? ''}
            name="q"
            placeholder="영상 제목, 문의 내용, 문의자, 주제로 검색해 보세요"
            type="search"
          />
          <button disabled={isNavigating} type="submit">
            검색
          </button>
        </form>

        <div className={styles.filterRow}>
          <div aria-label="문의 상태" className={styles.statusFilters} role="group">
            {filters.map(({ value, label }) => (
              <button
                aria-pressed={board.status === value}
                disabled={isNavigating}
                key={value}
                onClick={() =>
                  onLocationChange({ status: value === 'all' ? null : value, page: null })
                }
                type="button"
              >
                {label}
                <span>{board.counts[value]}</span>
              </button>
            ))}
          </div>
          <div className={styles.sortControl}>
            <ArrowDownWideNarrow aria-hidden="true" />
            <label className={styles.srOnly} htmlFor="inquiry-sort">
              문의 정렬 기준
            </label>
            <select
              disabled={isNavigating}
              id="inquiry-sort"
              onChange={(event) =>
                onLocationChange({
                  sort: event.target.value === 'time' ? null : event.target.value,
                  page: null,
                })
              }
              value={board.sort}
            >
              <option value="time">시간순 · 최신순</option>
              <option value="requester">문의자순 · 가나다순</option>
              <option value="topic">주제순 · 가나다순</option>
            </select>
          </div>
        </div>

        <div className={styles.listCaption}>
          <p aria-live="polite" role="status">
            총 <strong>{board.total}</strong>개의 문의
            {board.total > 0
              ? ` · ${(board.page - 1) * 10 + 1}–${Math.min(board.page * 10, board.total)}`
              : ''}
          </p>
          <span>
            <SlidersHorizontal aria-hidden="true" />
            10개씩 보기
          </span>
        </div>
        <ul aria-label="문의 영상 목록" className={styles.list}>
          {board.rows.map((item) => (
            <li key={item.id}>
              <button
                className={styles.inquiryRow}
                disabled={isNavigating}
                onClick={() => onInquirySelect(item.id)}
                type="button"
              >
                <span
                  aria-label={`${item.sceneTitle} 썸네일`}
                  className={styles.thumbnail}
                  data-image={item.thumbnail}
                  role="img"
                >
                  {item.thumbnail === 'weather' ? (
                    <CloudRain aria-hidden="true" />
                  ) : item.thumbnail === 'square' ? (
                    <Building2 aria-hidden="true" />
                  ) : null}
                  <small>{item.timecode}</small>
                </span>
                <span className={styles.rowCopy}>
                  <span className={styles.rowMeta}>
                    {item.topic}
                    <i aria-hidden="true" />
                    {item.requester}
                    <span className={styles.statusChip} data-status={getBoardStatus(item.status)}>
                      {statusLabels[getBoardStatus(item.status)]}
                    </span>
                    {item.isDegraded ? (
                      <span className={styles.warning}>일부 검색 기능 제한</span>
                    ) : null}
                  </span>
                  <strong>{item.sceneTitle}</strong>
                  <span className={styles.comment}>
                    {item.comment || '추가 설명 없이 접수된 문의입니다.'}
                  </span>
                </span>
                <span className={styles.age}>
                  {item.daysAgo === 0 ? '오늘' : `${item.daysAgo}일 전`}
                  <ChevronRight aria-hidden="true" />
                </span>
              </button>
            </li>
          ))}
        </ul>
        {board.total === 0 ? (
          <div className={styles.empty}>
            <Inbox aria-hidden="true" />
            <h3>조건에 맞는 문의가 없어요</h3>
            <p>검색어나 상태 필터를 바꿔 보세요.</p>
            <button
              disabled={isNavigating}
              onClick={() => onLocationChange({ q: null, status: null, page: null })}
              type="button"
            >
              검색 조건 초기화
            </button>
          </div>
        ) : null}
        {board.total > 0 ? (
          <nav aria-label="문의 목록 페이지" className={styles.pagination}>
            <button
              aria-label="이전 페이지"
              disabled={isNavigating || board.page === 1}
              onClick={() => handlePageChange(board.page - 1)}
              type="button"
            >
              <ChevronLeft aria-hidden="true" />
            </button>
            {Array.from({ length: board.pageCount }, (_, index) => index + 1).map((page) => (
              <button
                aria-current={page === board.page ? 'page' : undefined}
                aria-label={`${page}페이지`}
                disabled={isNavigating}
                key={page}
                onClick={() => handlePageChange(page)}
                type="button"
              >
                {page}
              </button>
            ))}
            <button
              aria-label="다음 페이지"
              disabled={isNavigating || board.page === board.pageCount}
              onClick={() => handlePageChange(board.page + 1)}
              type="button"
            >
              <ChevronRight aria-hidden="true" />
            </button>
          </nav>
        ) : null}
      </section>
    </div>
  );
}
