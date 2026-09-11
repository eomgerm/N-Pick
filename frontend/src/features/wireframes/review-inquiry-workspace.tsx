'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Film,
  Inbox,
  Plus,
  RefreshCw,
  UserCheck,
} from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { AppShell } from '@/components/app-shell';
import { useMember } from '@/components/session-boundary';
import {
  inquiryResolutionLabels,
  inquiryStatusLabels,
  type InquiryStatus,
} from '@/features/wireframes/inquiry-state';
import {
  claimReviewInquiry,
  getReviewInquiries,
  getReviewInquiry,
  type ReviewInquiryDetail,
  type ReviewInquiryList,
} from '@/features/wireframes/review-inquiry-api';
import {
  getSnapshotFacts,
  displayClipTitle,
  getClaimRecovery,
  getFilterFacts,
  evidenceLabel,
} from '@/features/wireframes/review-inquiry-view';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ResolutionSummary } from '@/features/wireframes/reviewer-resolution';
import { getResolutionSummary } from '@/features/wireframes/reviewer-resolution-state';
import boardStyles from '@/features/wireframes/reviewer-board.module.css';
import styles from '@/features/wireframes/reviewer.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import { createIdempotencyKey } from '@/lib/api/idempotency';

const filters: Array<{ value: 'all' | InquiryStatus; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'open', label: '접수' },
  { value: 'reviewing', label: '검수 중' },
  { value: 'closed', label: '종료' },
];

function selectedStatus(value: string | null): InquiryStatus | undefined {
  return value === 'open' || value === 'reviewing' || value === 'closed' ? value : undefined;
}

function selectedPage(value: string | null): number {
  const page = Number(value ?? '1');
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

function formatDate(value: string | null): string {
  if (!value) return '기록 없음';
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function formatTimecode(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [hours, minutes, seconds].map((part) => String(part).padStart(2, '0')).join(':');
}

function InquiryList({
  data,
  currentStatus,
}: {
  data: ReviewInquiryList;
  currentStatus?: InquiryStatus;
}) {
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
                      {formatTimecode(item.scene.startTimeMs)}–
                      {formatTimecode(item.scene.endTimeMs)}
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
                      {formatTimecode(item.scene.startTimeMs)}–
                      {formatTimecode(item.scene.endTimeMs)}
                    </span>
                    <span className={boardStyles.comment}>{item.queryText}</span>
                  </span>
                  <span className={boardStyles.age}>{formatDate(item.createdAt)}</span>
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

function FilterSnapshot({ value }: { value: string | null }) {
  const facts = getFilterFacts(value);
  return (
    <section className="rounded-2xl border border-(--line) p-5" aria-labelledby="filter-title">
      <h2 className="font-bold" id="filter-title">
        문의 당시 검색 조건
      </h2>
      {facts === null ? (
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 조건을 확인할 수 없습니다.</p>
      ) : facts.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">직접 선택한 검색 조건이 없습니다.</p>
      ) : (
        <dl className="mt-4 grid gap-3 text-sm md:grid-cols-2">
          {facts.map((fact, index) => (
            <div className="min-w-0" key={`${fact.label}-${index}`}>
              <dt className="text-(--muted)">{fact.label}</dt>
              <dd className="mt-1 wrap-anywhere">{fact.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function SearchInterpretation({ value }: { value: string | null }) {
  if (!value) {
    return (
      <section className="rounded-2xl border border-(--line) p-5">
        <h2 className="font-bold">문의 당시 검색 해석</h2>
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 해석이 없습니다.</p>
      </section>
    );
  }
  try {
    getResolutionSummary(value);
  } catch {
    return (
      <section className="rounded-2xl border border-(--line) p-5">
        <h2 className="font-bold">문의 당시 검색 해석</h2>
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 해석을 확인할 수 없습니다.</p>
      </section>
    );
  }
  return <ResolutionSummary title="문의 당시 검색 해석" value={value} />;
}

function SnapshotCount({ label, value }: { label: string; value: string | null }) {
  const facts = getSnapshotFacts(value);
  return (
    <div className="rounded-xl bg-(--surface-muted) p-4">
      <dt className="text-xs font-bold text-(--muted)">{label}</dt>
      <dd className="mt-2 font-semibold">
        {facts === null ? (
          '이 형식의 기록은 아직 표시할 수 없습니다.'
        ) : facts.length === 0 ? (
          '없음'
        ) : (
          <ul className="space-y-2">
            {facts.map((fact, index) => (
              <li className="wrap-anywhere" key={index}>
                {fact.label}: {fact.value}
              </li>
            ))}
          </ul>
        )}
      </dd>
    </div>
  );
}

function InquiryDetail({ feedbackId }: { feedbackId: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const claimKey = useRef<string | null>(null);
  const detailTitleRef = useRef<HTMLHeadingElement>(null);
  const detailQueryKey = ['review-inquiry', feedbackId] as const;
  const detail = useQuery({
    queryKey: detailQueryKey,
    queryFn: ({ signal }) => getReviewInquiry(feedbackId, signal),
  });
  const loadedFeedbackId = detail.data?.feedbackId;
  const claim = useMutation({
    mutationFn: async () => {
      claimKey.current ??= createIdempotencyKey();
      await claimReviewInquiry(feedbackId, claimKey.current);
    },
    onSuccess: async () => {
      claimKey.current = null;
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: detailQueryKey }),
      ]);
    },
  });

  useEffect(() => {
    if (loadedFeedbackId) detailTitleRef.current?.focus({ preventScroll: true });
  }, [loadedFeedbackId]);

  function back() {
    router.push(getReviewUrl(pathname, searchParams.toString(), { inquiry: null }), {
      scroll: false,
    });
  }

  async function recoverClaim() {
    const recovery = getClaimRecovery(claim.error);
    if (recovery.action === 'retry') {
      claim.mutate();
      return;
    }
    if (recovery.action === 'back') {
      back();
      return;
    }
    await Promise.all([
      detail.refetch(),
      queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
    ]);
  }

  if (detail.isPending) {
    return (
      <p aria-busy="true" className="mx-auto max-w-5xl py-24 text-center" role="status">
        문의 상세를 불러오는 중…
      </p>
    );
  }
  if (detail.isError) {
    return (
      <div className="mx-auto max-w-3xl py-12">
        <button className={boardStyles.backButton} onClick={back} type="button">
          <ArrowLeft aria-hidden="true" /> 문의 목록으로
        </button>
        <ApiErrorNotice error={detail.error} />
        <button className="mt-4 underline" onClick={() => detail.refetch()} type="button">
          다시 시도
        </button>
      </div>
    );
  }

  const inquiry: ReviewInquiryDetail = detail.data;
  const clipTitle = displayClipTitle(inquiry.scene.clipTitle);
  const claimRecovery = claim.isError ? getClaimRecovery(claim.error) : null;
  return (
    <div className="mx-auto max-w-5xl">
      <button className={boardStyles.backButton} onClick={back} type="button">
        <ArrowLeft aria-hidden="true" /> 문의 목록으로
      </button>
      <section className={styles.hero}>
        <div>
          <p className={styles.eyebrow}>문의 #{inquiry.feedbackId}</p>
          <h1 ref={detailTitleRef} tabIndex={-1}>
            {clipTitle}
          </h1>
          <p>
            {formatDate(inquiry.createdAt)} · 검색 결과 #{inquiry.resultRank}
          </p>
        </div>
        <span className={styles.largeStatus} data-status={inquiry.status}>
          {inquiry.status === 'open' ? (
            <Clock3 aria-hidden="true" />
          ) : inquiry.status === 'reviewing' ? (
            <RefreshCw aria-hidden="true" />
          ) : (
            <CheckCircle2 aria-hidden="true" />
          )}
          {inquiryStatusLabels[inquiry.status]}
        </span>
      </section>

      <article className="space-y-5 rounded-3xl border border-(--line) bg-(--surface) p-6 shadow-sm md:p-8">
        <section className="grid gap-4 md:grid-cols-2">
          <div className="rounded-2xl bg-(--surface-muted) p-5">
            <p className="text-xs font-bold text-(--muted)">당시 검색어</p>
            <strong className="mt-2 block">{inquiry.execution.queryText}</strong>
          </div>
          <div className="rounded-2xl bg-(--surface-muted) p-5">
            <p className="text-xs font-bold text-(--muted)">문의 내용</p>
            <strong className="mt-2 block">
              {inquiry.comment || '추가 설명 없이 접수된 문의입니다.'}
            </strong>
          </div>
        </section>

        <section className="rounded-2xl border border-(--line) p-5">
          <h2 className="font-bold">문의 장면</h2>
          <dl className="mt-4 grid gap-3 text-sm md:grid-cols-2">
            <div>
              <dt className="text-(--muted)">클립 / 장면 ID</dt>
              <dd>
                {inquiry.scene.clipId} / {inquiry.sceneId}
              </dd>
            </div>
            <div>
              <dt className="text-(--muted)">구간</dt>
              <dd>
                {formatTimecode(inquiry.scene.startTimeMs)}–
                {formatTimecode(inquiry.scene.endTimeMs)}
              </dd>
            </div>
            <div>
              <dt className="text-(--muted)">처리 실행</dt>
              <dd>
                {inquiry.scene.pipelineRunId} · #{inquiry.scene.processingNo}
              </dd>
            </div>
          </dl>
        </section>

        {inquiry.status === 'open' ? (
          <section className={styles.claimPanel}>
            <UserCheck aria-hidden="true" />
            <div>
              <strong>아직 담당자가 없습니다.</strong>
              <p>선점에 성공한 검수자만 처리할 수 있습니다.</p>
            </div>
            <button
              className={styles.primaryButton}
              disabled={claim.isPending}
              onClick={() => claim.mutate()}
              type="button"
            >
              {claim.isPending ? '검수 시작 중…' : '검수 시작'}
            </button>
          </section>
        ) : null}
        <p aria-live="polite" className="sr-only" role="status">
          {claim.isPending
            ? '검수 시작 요청을 처리하고 있습니다.'
            : claim.isSuccess
              ? '검수 시작 요청이 성공했습니다.'
              : ''}
        </p>
        {claimRecovery ? (
          <section className="space-y-3" aria-label="검수 시작 실패 안내">
            <ApiErrorNotice error={claim.error} />
            <p className="text-sm">{claimRecovery.message}</p>
            <button
              className={styles.secondaryButton}
              disabled={claim.isPending}
              onClick={() => void recoverClaim()}
              type="button"
            >
              {claimRecovery.actionLabel}
            </button>
          </section>
        ) : null}

        {inquiry.status !== 'open' ? (
          <section className="rounded-2xl border border-(--line) p-5">
            <h2 className="font-bold">검수 이력</h2>
            <p className="mt-2 text-sm">
              {inquiry.history.reviewerName || inquiry.history.reviewerLoginId || '담당 검수자'} ·{' '}
              {inquiry.history.reviewStartedAt
                ? formatDate(inquiry.history.reviewStartedAt)
                : '시작 시각 확인 중'}
            </p>
          </section>
        ) : null}

        {inquiry.resolution ? (
          <section
            className={`rounded-2xl border border-(--line) p-5 ${
              inquiry.resolution === 'deferred'
                ? 'bg-(--warning-soft)'
                : inquiry.resolution === 'no_action'
                  ? 'bg-(--surface-muted)'
                  : 'bg-(--positive-soft)'
            }`}
          >
            <h2 className="font-bold">{inquiry.status === 'closed' ? '처리 결과' : '현재 판정'}</h2>
            <p className="mt-2">{inquiryResolutionLabels[inquiry.resolution]}</p>
            <p className="mt-1 text-sm text-(--muted)">
              {inquiry.resolutionNote || '추가 사유 없음'}
            </p>
            {inquiry.status === 'reviewing' ? (
              <p className="mt-3 text-sm font-semibold">
                검증과 반영이 끝나기 전까지 이 문의는 검수 중입니다.
              </p>
            ) : null}
          </section>
        ) : null}
        {inquiry.status === 'closed' && !inquiry.resolution ? (
          <p className="rounded-2xl border border-(--line) bg-(--warning-soft) p-5" role="alert">
            종료 상태이지만 처리 결과 기록을 확인할 수 없습니다. 최신 상태를 다시 확인해 주세요.
          </p>
        ) : null}

        <section aria-labelledby="result-comparison-title">
          <h2 className="font-bold" id="result-comparison-title">
            문의 당시 결과와 현재 태그
          </h2>
          <p className="mt-2 text-sm text-(--muted)">
            당시 검색 기록은 읽기 전용이며 현재 태그가 바뀌어도 덮어쓰지 않습니다.
          </p>
          <div className="mt-4 grid gap-4 md:grid-cols-2">
            <section className="rounded-2xl border border-(--line) p-5">
              <h3 className="font-bold">문의 당시 검색 결과</h3>
              <p className="mt-2 text-sm">
                검색 결과 <strong>#{inquiry.resultRank}</strong>로 저장됨
              </p>
              <dl className="mt-4 grid gap-2">
                <SnapshotCount label="결과 설명" value={inquiry.resultExplainJson} />
                <SnapshotCount label="적용 규칙" value={inquiry.execution.appliedRulesJson} />
                <SnapshotCount label="장면 제외" value={inquiry.execution.appliedExcludesJson} />
              </dl>
            </section>
            <section className="rounded-2xl border border-(--line) p-5">
              <h3 className="font-bold">현재 태그</h3>
              {inquiry.evidence.length === 0 ? (
                <p className="mt-3 text-sm text-(--muted)">현재 표시할 태그가 없습니다.</p>
              ) : (
                <ul className="mt-4 flex flex-wrap gap-2" aria-label="현재 장면과 영상의 태그">
                  {inquiry.evidence.map((evidence, index) => (
                    <li
                      className="rounded-full bg-(--accent-soft) px-3 py-2 text-sm font-semibold text-(--accent-strong)"
                      key={`${evidence.taggingId}-${index}`}
                    >
                      <strong>{evidence.tagName}</strong>
                      <span className="mt-1 block text-xs text-(--muted)">
                        출처: {evidenceLabel(evidence.source)} · 검증:{' '}
                        {evidenceLabel(evidence.verifiedState)} · 범위:{' '}
                        {evidenceLabel(evidence.scope)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </section>

        <FilterSnapshot value={inquiry.execution.explicitFiltersJson} />
        <SearchInterpretation value={inquiry.execution.parsedQueryJson} />
      </article>
    </div>
  );
}

export function ReviewInquiryWorkspace({ theme }: { theme: WireframeTheme }) {
  const member = useMember();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const status = selectedStatus(searchParams.get('status'));
  const page = selectedPage(searchParams.get('page'));
  const feedbackId = searchParams.get('inquiry');
  const list = useQuery({
    queryKey: ['review-inquiries', status ?? 'all', page],
    queryFn: ({ signal }) => getReviewInquiries(page - 1, status, signal),
    enabled: feedbackId === null,
  });

  useEffect(() => {
    if (feedbackId !== null || !list.data) return;
    const normalizedPage = list.data.totalPages === 0 ? 1 : Math.min(page, list.data.totalPages);
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
