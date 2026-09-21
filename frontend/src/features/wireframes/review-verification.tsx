'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { confirmCorrection, confirmErrorMessages } from '@/features/wireframes/review-confirm-api';
import {
  verificationErrorMessages,
  verifyCorrectionCandidates,
  type DroppedReason,
  type VerificationResult,
} from '@/features/wireframes/review-verification-api';
import styles from '@/features/wireframes/reviewer.module.css';
import { ApiClientError } from '@/lib/api/error';

const droppedReasonLabels: Record<DroppedReason, string> = {
  approved_scene_exclusion: '승인된 장면 제외 규칙',
  false_hit_guard: '오탐 방지 판정',
  score_drop: '순위·컷오프 이탈',
};

interface CorrectionVerificationPanelProps {
  feedbackId: string;
  onVerified?: (result: VerificationResult) => void;
}

export function CorrectionVerificationPanel({
  feedbackId,
  onVerified,
}: CorrectionVerificationPanelProps) {
  const queryClient = useQueryClient();
  // 같은 executionId 재요청은 서버가 멱등 성공으로 돌려주므로 재시도만 막는다.
  const confirmation = useMutation({
    mutationFn: (executionId: string) => confirmCorrection(feedbackId, executionId),
    retry: false,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
      ]);
    },
  });
  // ponytail: 후보 조회 GET 이 없어 검증 결과는 세션 한정. 새로고침 후에는 다시 검증으로 복구한다.
  const verification = useMutation({
    mutationFn: () => verifyCorrectionCandidates(feedbackId),
    retry: false,
    onSuccess: onVerified,
  });
  const result = verification.data;
  const guidance =
    verification.error instanceof ApiClientError
      ? verificationErrorMessages[verification.error.code]
      : undefined;
  const confirmGuidance =
    confirmation.error instanceof ApiClientError
      ? confirmErrorMessages[confirmation.error.code]
      : undefined;

  return (
    <section
      aria-labelledby="verification-title"
      className="rounded-2xl border border-(--line) p-5"
    >
      <h2 className="font-bold" id="verification-title">
        교정 후보 검증 재검색
      </h2>
      <p className="mt-2 text-sm text-(--muted)">
        원 신고의 검색어와 필터로 다시 검색합니다. 후보는 이 요청에만 임시로 적용되며 일반 검색은
        바뀌지 않습니다.
      </p>
      <p className="mt-1 text-sm font-semibold">
        검증 성공은 자동 승인이 아닙니다. 결과를 확인한 뒤 별도로 확정해야 반영됩니다.
      </p>
      <button
        className={`${styles.primaryButton} mt-4`}
        disabled={verification.isPending}
        onClick={() => {
          confirmation.reset();
          verification.mutate();
        }}
        type="button"
      >
        {verification.isPending ? '검증 중…' : result ? '다시 검증' : '후보 검증'}
      </button>
      <p aria-live="polite" className="sr-only" role="status">
        {verification.isPending
          ? '교정 후보 검증 재검색을 진행하고 있습니다.'
          : result
            ? `검증 재검색이 끝났습니다. 새로 들어온 장면 ${result.enteredScenes.length}개, 빠진 장면 ${result.droppedScenes.length}개입니다.`
            : ''}
      </p>

      {verification.isError ? (
        <div className="mt-4 space-y-3">
          <ApiErrorNotice error={verification.error} />
          {guidance ? <p className="text-sm">{guidance}</p> : null}
        </div>
      ) : null}

      {result ? (
        <div className="mt-4 grid gap-4">
          <p className="text-sm text-(--muted)">검증 실행 #{result.executionId}</p>
          <section>
            <h3 className="text-sm font-bold">새로 들어온 장면 ({result.enteredScenes.length})</h3>
            {result.enteredScenes.length === 0 ? (
              <p className="mt-2 text-sm text-(--muted)">새로 들어온 장면이 없습니다.</p>
            ) : (
              <ul className="mt-2 flex flex-wrap gap-2 text-sm">
                {result.enteredScenes.map(({ sceneId }) => (
                  <li
                    className="rounded-lg border border-(--line) bg-(--positive-soft) px-2.5 py-1"
                    key={sceneId}
                  >
                    장면 {sceneId}
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <h3 className="text-sm font-bold">빠진 장면 ({result.droppedScenes.length})</h3>
            {result.droppedScenes.length === 0 ? (
              <p className="mt-2 text-sm text-(--muted)">빠진 장면이 없습니다.</p>
            ) : (
              <ul className="mt-2 grid gap-2 text-sm">
                {result.droppedScenes.map(({ sceneId, reason }) => (
                  <li
                    className="rounded-lg border border-(--line) bg-(--warning-soft) px-2.5 py-1"
                    key={sceneId}
                  >
                    장면 {sceneId} · {droppedReasonLabels[reason]}
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <h3 className="text-sm font-bold">적용된 규칙 ({result.verificationRuleSet.length})</h3>
            {result.verificationRuleSet.length === 0 ? (
              <p className="mt-2 text-sm text-(--muted)">적용된 해석 규칙이 없습니다.</p>
            ) : (
              <ul className="mt-2 flex flex-wrap gap-2 text-sm">
                {result.verificationRuleSet.map((ruleId) => (
                  <li
                    className="rounded-lg border border-(--line) bg-(--surface-muted) px-2.5 py-1"
                    key={ruleId}
                  >
                    규칙 {ruleId}
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section className="border-t border-(--line) pt-4">
            <h3 className="text-sm font-bold">교정 확정</h3>
            <p className="mt-2 text-sm text-(--muted)">
              이 검증 실행을 근거로 교정을 확정하고 문의를 종료합니다.
            </p>
            <button
              className={`${styles.primaryButton} mt-3`}
              disabled={confirmation.isPending || !result.executionId}
              onClick={() => confirmation.mutate(result.executionId)}
              type="button"
            >
              {confirmation.isPending ? '확정 중…' : '교정 확정'}
            </button>
            <p aria-live="polite" className="sr-only" role="status">
              {confirmation.isPending
                ? '교정 확정 요청을 처리하고 있습니다.'
                : confirmation.isSuccess
                  ? '교정을 확정하고 문의를 종료했습니다.'
                  : ''}
            </p>
            {confirmation.isSuccess ? (
              <p className="mt-3 text-sm text-(--positive)">교정을 확정하고 문의를 종료했습니다.</p>
            ) : null}
            {confirmation.isError ? (
              <div className="mt-3 space-y-3">
                <ApiErrorNotice error={confirmation.error} />
                {confirmGuidance ? <p className="text-sm">{confirmGuidance}</p> : null}
              </div>
            ) : null}
          </section>
        </div>
      ) : null}
    </section>
  );
}
