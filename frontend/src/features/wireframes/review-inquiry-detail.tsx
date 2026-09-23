'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowRight,
  Check,
  CheckCircle2,
  Clock3,
  Film,
  History,
  MessageSquareText,
  RefreshCw,
  Search,
  Tags,
  UserCheck,
} from 'lucide-react';
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
import { ReviewInquiryTags } from '@/features/wireframes/review-inquiry-tags';
import { ParsePatchCandidateForm } from '@/features/wireframes/review-parse-patch';
import { CorrectionVerificationPanel } from '@/features/wireframes/review-verification';
import {
  displayClipTitle,
  getClaimRecovery,
  formatInquiryDate,
  formatInquiryTimecode,
  inquiryResolutionClasses,
} from '@/features/wireframes/review-inquiry-view';
import { SceneExcludeCandidateForm } from '@/features/wireframes/review-scene-exclude';
import { ReviewInquiryPreview } from '@/features/wireframes/reviewer-scene-preview';
import styles from '@/features/wireframes/review-inquiry-detail.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import { createIdempotencyKey } from '@/lib/api/idempotency';

interface InquiryDetailProps {
  feedbackId: string;
  theme: WireframeTheme;
  onBack: () => void;
}

export function InquiryDetail({ feedbackId, theme, onBack }: InquiryDetailProps) {
  const member = useMember();
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

  async function recoverClaim() {
    const recovery = getClaimRecovery(claim.error);
    if (recovery.action === 'retry') {
      claim.mutate();
      return;
    }
    if (recovery.action === 'back') {
      onBack();
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
      <div className={styles.detail}>
        <div aria-busy="true" className={styles.loading} role="status">
          <span className="sr-only">문의 상세를 불러오는 중…</span>
          <div aria-hidden="true" className={styles.skeletonHeading} />
          <div aria-hidden="true" className={styles.skeletonBody} />
        </div>
      </div>
    );
  }
  if (detail.isError) {
    return (
      <div className={styles.detail}>
        <section className={`${styles.panel} p-6 sm:p-8`}>
          <h2 className="mb-4 text-xl font-semibold">문의 상세를 불러오지 못했어요</h2>
          <ApiErrorNotice error={detail.error} />
          <button
            className={`${styles.secondaryButton} mt-5`}
            disabled={detail.isFetching}
            onClick={() => detail.refetch()}
            type="button"
          >
            <RefreshCw aria-hidden="true" /> {detail.isFetching ? '다시 불러오는 중…' : '다시 시도'}
          </button>
        </section>
      </div>
    );
  }

  const inquiry: ReviewInquiryDetail = detail.data;
  const clipTitle = displayClipTitle(inquiry.scene.clipTitle);
  const claimRecovery = claim.isError ? getClaimRecovery(claim.error) : null;
  const statusIndex = ['open', 'reviewing', 'closed'].indexOf(inquiry.status);
  const hasCorrection =
    inquiry.status === 'reviewing' &&
    (inquiry.resolution === 'tag_correction' ||
      inquiry.resolution === 'patch_parse' ||
      inquiry.resolution === 'exclude_scene');

  return (
    <div className={styles.detail}>
      <article className={styles.workspace}>
        <header className={styles.heading}>
          <div className="min-w-0">
            <h2 ref={detailTitleRef} tabIndex={-1}>
              {clipTitle}
            </h2>
            <p className={styles.meta}>
              <span>
                <Clock3 aria-hidden="true" /> {formatInquiryDate(inquiry.createdAt)} 접수
              </span>
              <span>
                검색 결과 <strong>#{inquiry.resultRank}</strong>
              </span>
              <span>
                문의 <strong>#{inquiry.feedbackId}</strong>
              </span>
            </p>
          </div>
          <span className={styles.status} data-status={inquiry.status}>
            {inquiry.status === 'open' ? (
              <Clock3 aria-hidden="true" />
            ) : inquiry.status === 'reviewing' ? (
              <RefreshCw aria-hidden="true" />
            ) : (
              <CheckCircle2 aria-hidden="true" />
            )}
            {inquiryStatusLabels[inquiry.status]}
          </span>
        </header>

        <div className={styles.columns}>
          <div className={styles.context}>
            <section
              className={`${styles.panel} ${styles.brief}`}
              aria-labelledby="inquiry-content-title"
            >
              <h2 className={styles.sectionTitle} id="inquiry-content-title">
                <MessageSquareText aria-hidden="true" /> 문의 내용
              </h2>
              <p className={styles.comment}>
                {inquiry.comment || '추가 설명 없이 접수된 문의입니다.'}
              </p>
              <div className={styles.query}>
                <Search aria-hidden="true" />
                <div className="min-w-0">
                  <p>당시 검색어</p>
                  <strong>{inquiry.execution.queryText}</strong>
                </div>
              </div>
            </section>

            <section
              className={`${styles.panel} ${styles.scene}`}
              aria-labelledby="inquiry-scene-title"
            >
              <div className={styles.sectionHeading}>
                <h2 className={styles.sectionTitle} id="inquiry-scene-title">
                  <Film aria-hidden="true" /> 문의 장면
                </h2>
                <ReviewInquiryPreview key={inquiry.feedbackId} inquiry={inquiry} theme={theme} />
              </div>
              <div className={styles.timeRange}>
                <span className={styles.timeLabel}>장면 구간</span>
                <p>
                  <span>{formatInquiryTimecode(inquiry.scene.startTimeMs)}</span>
                  <span className={styles.timeConnector} aria-label="부터">
                    <ArrowRight aria-hidden="true" />
                  </span>
                  <span>{formatInquiryTimecode(inquiry.scene.endTimeMs)}</span>
                </p>
              </div>
              <dl className={styles.sceneFacts}>
                <div>
                  <dt>클립 ID</dt>
                  <dd>{inquiry.scene.clipId}</dd>
                </div>
                <div>
                  <dt>장면 ID</dt>
                  <dd>{inquiry.sceneId}</dd>
                </div>
                <div>
                  <dt>처리 실행</dt>
                  <dd>
                    {inquiry.scene.pipelineRunId} · #{inquiry.scene.processingNo}
                  </dd>
                </div>
              </dl>
            </section>
          </div>

          <aside className={`${styles.panel} ${styles.actions}`} aria-label="문의 검수">
            <h2 className={styles.sectionTitle}>
              <UserCheck aria-hidden="true" /> 검수 진행
            </h2>
            <ol className={styles.progress} aria-label="문의 진행 단계">
              {(['open', 'reviewing', 'closed'] as const).map((status, index) => (
                <li
                  key={status}
                  data-reached={index <= statusIndex}
                  aria-current={status === inquiry.status ? 'step' : undefined}
                >
                  <span className={styles.stepNumber}>
                    {index < statusIndex ? <Check aria-hidden="true" /> : index + 1}
                  </span>
                  <span>{inquiryStatusLabels[status]}</span>
                </li>
              ))}
            </ol>

            {inquiry.status === 'open' ? (
              <section className={styles.claimPanel}>
                <h3>아직 담당자가 없습니다.</h3>
                <p>
                  문의 내용과 장면을 확인한 뒤 검수를 시작해 주세요. 선점에 성공한 아카이브 팀이
                  담당합니다.
                </p>
                <button
                  className={styles.primaryButton}
                  disabled={claim.isPending}
                  onClick={() => claim.mutate()}
                  type="button"
                >
                  <UserCheck aria-hidden="true" /> {claim.isPending ? '검수 시작 중…' : '검수 시작'}
                  <ArrowRight aria-hidden="true" className="ml-auto" />
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
              <section className="mt-5 space-y-3" aria-label="검수 시작 실패 안내">
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
              <section className={styles.history}>
                <h3>
                  <History aria-hidden="true" /> 검수 이력
                </h3>
                <p>
                  {inquiry.history.reviewerName ||
                    inquiry.history.reviewerLoginId ||
                    '담당 아카이브 팀'}
                </p>
                <span>
                  {inquiry.history.reviewStartedAt
                    ? formatInquiryDate(inquiry.history.reviewStartedAt)
                    : '시작 시각 확인 중'}
                </span>
              </section>
            ) : null}
            {inquiry.status === 'reviewing' ? (
              <InquiryResolutionForm
                inquiry={inquiry}
                key={`${inquiry.feedbackId}-${inquiry.resolution ?? 'new'}`}
                memberLoginId={member.loginId}
              />
            ) : null}
            {inquiry.resolution ? (
              <section
                className={`${styles.outcome} ${inquiryResolutionClasses[inquiry.resolution]}`}
              >
                <h2>{inquiry.status === 'closed' ? '처리 결과' : '현재 판정'}</h2>
                <p className="mt-2 font-semibold">{inquiryResolutionLabels[inquiry.resolution]}</p>
                <p
                  aria-label="처리 사유"
                  className={`${styles.outcomeNote} mt-2 whitespace-pre-wrap text-(--muted)`}
                  role="region"
                  tabIndex={0}
                >
                  {inquiry.resolutionNote || '추가 사유 없음'}
                </p>
                {inquiry.status === 'reviewing' ? (
                  <p className="mt-3 text-xs">
                    검증과 반영이 끝나기 전까지 이 문의는 검수 중입니다.
                  </p>
                ) : null}
              </section>
            ) : null}
            {inquiry.status === 'closed' && !inquiry.resolution ? (
              <p className="mt-5 rounded-xl bg-(--warning-soft) p-4 text-sm" role="alert">
                종료 상태이지만 처리 결과 기록을 확인할 수 없습니다. 최신 상태를 다시 확인해 주세요.
              </p>
            ) : null}
          </aside>
        </div>

        <section
          className={`${styles.panel} ${styles.evidence}`}
          aria-labelledby="result-comparison-title"
        >
          <div className={styles.sectionHeading}>
            <h2 className={styles.sectionTitle} id="result-comparison-title">
              <Tags aria-hidden="true" /> 문의 당시 결과와 현재 태그
            </h2>
            <span className={styles.caption}>검색 기록 · 현재 근거 비교</span>
          </div>
          <p className={styles.help}>
            당시 검색 기록은 읽기 전용이며 현재 태그가 바뀌어도 덮어쓰지 않습니다.
          </p>
          <div className={styles.comparison}>
            <section className={styles.snapshot}>
              <div className={styles.sectionHeading}>
                <h3>문의 당시 검색 결과</h3>
                <span className={styles.rank}>#{inquiry.resultRank}</span>
              </div>
              <dl className={styles.snapshotFacts}>
                <SnapshotCount label="결과 설명" value={inquiry.resultExplainJson} />
                <SnapshotCount label="적용 규칙" value={inquiry.execution.appliedRulesJson} />
                <SnapshotCount label="장면 제외" value={inquiry.execution.appliedExcludesJson} />
              </dl>
              <div className={styles.savedContext}>
                <FilterSnapshot value={inquiry.execution.explicitFiltersJson} />
                <SearchInterpretation value={inquiry.execution.parsedQueryJson} />
              </div>
            </section>
            <div className={styles.currentTags}>
              <ReviewInquiryTags inquiry={inquiry} memberLoginId={member.loginId} />
            </div>
          </div>
        </section>

        {hasCorrection ? (
          <section className={`${styles.panel} ${styles.correction}`} aria-label="교정 후보와 검증">
            {inquiry.resolution === 'patch_parse' ? (
              <ParsePatchCandidateForm feedbackId={inquiry.feedbackId} />
            ) : null}
            {inquiry.resolution === 'exclude_scene' ? (
              <SceneExcludeCandidateForm inquiry={inquiry} />
            ) : null}
            <CorrectionVerificationPanel feedbackId={inquiry.feedbackId} key={inquiry.feedbackId} />
          </section>
        ) : null}
      </article>
    </div>
  );
}
