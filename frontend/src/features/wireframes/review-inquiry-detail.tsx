'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, Clock3, RefreshCw, UserCheck } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { inquiryResolutionLabels, inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import {
  claimReviewInquiry,
  getReviewInquiry,
  type ReviewInquiryDetail,
} from '@/features/wireframes/review-inquiry-api';
import {
  FilterSnapshot,
  SearchInterpretation,
  SnapshotCount,
} from '@/features/wireframes/review-inquiry-snapshots';
import { InquiryResolutionForm } from '@/features/wireframes/review-inquiry-resolution';
import {
  displayClipTitle,
  getClaimRecovery,
  evidenceLabel,
  formatInquiryDate,
  formatInquiryTimecode,
  inquiryResolutionClasses,
} from '@/features/wireframes/review-inquiry-view';
import { SceneExcludeCandidateForm } from '@/features/wireframes/review-scene-exclude';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ReviewInquiryPreview } from '@/features/wireframes/reviewer-scene-preview';
import boardStyles from '@/features/wireframes/reviewer-board.module.css';
import styles from '@/features/wireframes/reviewer.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import { createIdempotencyKey } from '@/lib/api/idempotency';

interface InquiryDetailProps {
  feedbackId: string;
  theme: WireframeTheme;
}

export function InquiryDetail({ feedbackId, theme }: InquiryDetailProps) {
  const member = useMember();
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
    claim.reset();
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
            {formatInquiryDate(inquiry.createdAt)} · 검색 결과 #{inquiry.resultRank}
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
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-bold">문의 장면</h2>
            <ReviewInquiryPreview key={inquiry.feedbackId} inquiry={inquiry} theme={theme} />
          </div>
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
                {formatInquiryTimecode(inquiry.scene.startTimeMs)}–
                {formatInquiryTimecode(inquiry.scene.endTimeMs)}
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
              <p>선점에 성공한 아카이브 팀만 처리할 수 있습니다.</p>
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
              {inquiry.history.reviewerName ||
                inquiry.history.reviewerLoginId ||
                '담당 아카이브 팀'}{' '}
              ·{' '}
              {inquiry.history.reviewStartedAt
                ? formatInquiryDate(inquiry.history.reviewStartedAt)
                : '시작 시각 확인 중'}
            </p>
          </section>
        ) : null}

        {inquiry.status === 'reviewing' ? (
          <InquiryResolutionForm
            inquiry={inquiry}
            key={`${inquiry.feedbackId}-${inquiry.resolution ?? 'new'}`}
            memberLoginId={member.loginId}
          />
        ) : null}

        {inquiry.status === 'reviewing' && inquiry.resolution === 'exclude_scene' ? (
          <SceneExcludeCandidateForm inquiry={inquiry} />
        ) : null}

        {inquiry.resolution ? (
          <section
            className={`rounded-2xl border border-(--line) p-5 ${inquiryResolutionClasses[inquiry.resolution]}`}
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
                  {inquiry.evidence.map((tag) => (
                    <li
                      className="inline-flex items-center gap-2 rounded-lg border border-(--line) bg-(--surface) py-1.5 pr-3 pl-2.5 text-sm"
                      key={tag.taggingId}
                      title={`출처: ${tag.sources.map(evidenceLabel).join('·') || '기록 없음'} · 범위: ${evidenceLabel(tag.scope)}`}
                    >
                      <span
                        aria-hidden="true"
                        className={`size-1.5 shrink-0 rounded-full ${
                          tag.verifiedState === 'verified' ? 'bg-(--positive)' : 'bg-(--muted)'
                        }`}
                      />
                      <span className="font-semibold text-(--text)">{tag.tagName}</span>
                      <span className="text-xs text-(--muted)">{evidenceLabel(tag.verifiedState)}</span>
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
