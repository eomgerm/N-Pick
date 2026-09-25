'use client';

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { Play, RefreshCw } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { confirmCorrection, confirmErrorMessages } from '@/features/wireframes/review-confirm-api';
import {
  correctionCandidatesQueryKey,
  getCorrectionCandidates,
} from '@/features/wireframes/review-inquiry-api';
import { ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import {
  formatMediaTime,
  formatSceneDuration,
  getSceneThumbnailUrl,
} from '@/features/wireframes/scene-preview-media';
import { SceneThumbnail } from '@/features/wireframes/scene-thumbnail';
import { allWithoutSearchEffect } from '@/features/wireframes/tag-type-effect';
import {
  verificationErrorMessages,
  verifyCorrectionCandidates,
  type DroppedReason,
  type VerificationResult,
  type VerificationScene,
} from '@/features/wireframes/review-verification-api';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/reviewer.module.css';
import { ApiClientError } from '@/lib/api/error';

const droppedReasonLabels: Record<DroppedReason, string> = {
  approved_scene_exclusion: '승인된 장면 제외 규칙',
  false_hit_guard: '오탐 방지 판정',
  score_drop: '순위·컷오프 이탈',
};

interface CorrectionVerificationPanelProps {
  feedbackId: string;
  theme: WireframeTheme;
  onVerified?: (result: VerificationResult) => void;
}

const MAX_VISIBLE_SCENES = 2;

function VerificationSceneCard({
  scene,
  detail,
  tone,
  onPlay,
}: {
  scene: VerificationScene;
  detail: string;
  tone: 'entered' | 'dropped';
  onPlay: () => void;
}) {
  const title = scene.displayName ?? '제목 없는 영상';
  const thumbnailUrl = getSceneThumbnailUrl(scene.sceneId)!;
  return (
    <li className="grid grid-cols-[5.5rem_minmax(0,1fr)_2rem] items-center gap-3 rounded-xl border border-(--line) p-2.5">
      <span className="relative block aspect-video overflow-hidden rounded-lg bg-[#17243b]">
        <SceneThumbnail alt={`${title} 대표 이미지`} src={thumbnailUrl} />
      </span>
      <span className="min-w-0">
        <span
          className={`block text-xs font-bold ${tone === 'entered' ? 'text-(--positive)' : 'text-(--danger)'}`}
        >
          {tone === 'entered' ? '+ 새로 포함' : '− 검색에서 빠짐'}
        </span>
        <strong className="mt-0.5 block truncate text-sm" title={title}>
          {title}
        </strong>
        <span className="block truncate text-xs text-(--muted)" title={detail}>
          {formatMediaTime(scene.startTimeMs / 1000)}–{formatMediaTime(scene.endTimeMs / 1000)} ·{' '}
          {detail}
        </span>
      </span>
      <button
        aria-label={`${title} 장면 재생`}
        className="grid size-8 place-items-center rounded-full border border-(--line) text-(--accent-strong) transition-colors hover:border-(--accent) hover:bg-(--accent-soft)"
        onClick={onPlay}
        title="장면 재생"
        type="button"
      >
        <Play aria-hidden="true" className="size-3.5" fill="currentColor" />
      </button>
    </li>
  );
}

export function CorrectionVerificationPanel({
  feedbackId,
  theme,
  onVerified,
}: CorrectionVerificationPanelProps) {
  const queryClient = useQueryClient();
  // 해석 교정 저장이나 태그 후보 변경이 진행 중이면 검증 재검색이 옛 후보로 돌아가지 않도록 막는다
  // (편집기·태그 교정과 공유하는 키).
  const parseSavePending = useIsMutating({ mutationKey: ['parse-patch-save', feedbackId] }) > 0;
  const tagChangePending = useIsMutating({ mutationKey: ['tag-candidate-change', feedbackId] }) > 0;
  const savePending = parseSavePending || tagChangePending;
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [previewScene, setPreviewScene] = useState<{
    scene: VerificationScene;
    detail: string;
  } | null>(null);
  // 같은 executionId 재요청은 서버가 멱등 성공으로 돌려주므로 재시도만 막는다.
  const confirmation = useMutation({
    mutationKey: ['confirm-correction', feedbackId],
    mutationFn: (executionId: string) => confirmCorrection(feedbackId, executionId),
    retry: false,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
      ]);
    },
  });
  // 검증 결과 자체는 세션 한정이다. 새로고침 후에는 복원된 후보로 다시 검증한다.
  // 검증과 같은 시점에 대기 후보를 다시 읽어, 이번 검증에 들어간 후보 수를 함께 보여 준다 (S15P21A501-317).
  // 후보 조회가 실패해도 검증 결과는 그대로 보여 주고 후보 수만 생략한다.
  const verification = useMutation({
    mutationKey: ['verification-run', feedbackId],
    mutationFn: async () => {
      const [verified, applied] = await Promise.all([
        verifyCorrectionCandidates(feedbackId),
        queryClient
          .fetchQuery({
            queryKey: correctionCandidatesQueryKey(feedbackId),
            queryFn: ({ signal }) => getCorrectionCandidates(feedbackId, signal),
            staleTime: 0,
          })
          .catch(() => null),
      ]);
      return { verified, applied };
    },
    retry: false,
    onSuccess: ({ verified }) => onVerified?.(verified),
  });
  const result = verification.data?.verified;
  const applied = verification.data?.applied ?? null;
  const guidance =
    verification.error instanceof ApiClientError
      ? verificationErrorMessages[verification.error.code]
      : undefined;
  const confirmGuidance =
    confirmation.error instanceof ApiClientError
      ? confirmErrorMessages[confirmation.error.code]
      : undefined;
  const visibleEntered = result?.enteredScenes.slice(0, MAX_VISIBLE_SCENES) ?? [];
  const visibleDropped = result?.droppedScenes.slice(0, MAX_VISIBLE_SCENES) ?? [];

  return (
    <section
      aria-labelledby="verification-title"
      className="rounded-2xl border border-(--line) p-5"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="font-bold" id="verification-title">
            후보 검증
          </h2>
          <button
            aria-label={result ? '후보 다시 검증' : '후보 검증'}
            className="grid size-8 place-items-center rounded-full border border-(--line) text-(--accent-strong) transition-colors hover:border-(--accent) hover:bg-(--accent-soft) disabled:cursor-not-allowed disabled:opacity-45"
            disabled={verification.isPending || savePending}
            onClick={() => {
              confirmation.reset();
              setPreviewScene(null);
              verification.mutate();
            }}
            title={result ? '다시 검증' : '후보 검증'}
            type="button"
          >
            <RefreshCw
              aria-hidden="true"
              className={`size-3.5 ${verification.isPending ? 'motion-safe:animate-spin' : ''}`}
            />
          </button>
        </div>
        <span className="text-xs text-(--muted)">재검색으로 교정 결과를 확인한 뒤 확정하세요.</span>
      </div>
      <p aria-live="polite" className="sr-only" role="status">
        {verification.isPending
          ? '교정 후보 검증 재검색을 진행하고 있습니다.'
          : result
            ? `검증이 끝났습니다. 새로 들어온 장면 ${result.enteredScenes.length}개, 빠진 장면 ${result.droppedScenes.length}개입니다.`
            : ''}
      </p>

      {verification.isError ? (
        <div className="mt-4 space-y-3">
          <ApiErrorNotice
            error={verification.error}
            message={guidance ?? '검증 재검색을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'}
          />
        </div>
      ) : null}

      {result ? (
        <div className="mt-5 grid gap-4 border-t border-(--line) pt-4">
          <dl className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <div>
              <dt className="text-xs text-(--muted)">새로 들어온 장면</dt>
              <dd className="font-bold text-(--positive)">{result.enteredScenes.length}</dd>
            </div>
            <div>
              <dt className="text-xs text-(--muted)">빠진 장면</dt>
              <dd className="font-bold text-(--danger)">{result.droppedScenes.length}</dd>
            </div>
            <div>
              <dt className="text-xs text-(--muted)">반영된 교정</dt>
              <dd className="font-bold">{result.verificationRuleSet.length}</dd>
            </div>
          </dl>
          {applied ? (
            <div className="grid gap-1 text-xs text-(--muted)">
              <p>
                이번 검증에 적용된 후보: 태그 {applied.tags.length} · 해석 규칙{' '}
                {applied.parsePatches.length} · 장면 제외 {applied.sceneExcludes.length}
              </p>
              {allWithoutSearchEffect(applied.tags.map((tag) => tag.tagType)) ? (
                <p>
                  태그 후보가 모두 검색 결과에 영향을 주지 않는 유형이라, 태그 교정만으로는 검증
                  결과가 달라지지 않습니다.
                </p>
              ) : null}
            </div>
          ) : null}
          {visibleEntered.length > 0 ? (
            <div className="grid gap-2">
              <ul className="grid gap-2">
                {visibleEntered.map((scene) => {
                  const detail = scene.matchedKeywords.length
                    ? `일치: ${scene.matchedKeywords
                        .slice(0, 3)
                        .map(({ keyword }) => keyword)
                        .join(', ')}`
                    : '새 검색 조건과 일치';
                  return (
                    <VerificationSceneCard
                      detail={detail}
                      key={scene.sceneId}
                      onPlay={() => setPreviewScene({ scene, detail })}
                      scene={scene}
                      tone="entered"
                    />
                  );
                })}
              </ul>
              {result.enteredScenes.length > visibleEntered.length ? (
                <p className="text-xs text-(--muted)">
                  새로 들어온 장면 외 {result.enteredScenes.length - visibleEntered.length}개
                </p>
              ) : null}
            </div>
          ) : null}
          {visibleDropped.length > 0 ? (
            <div className="grid gap-2">
              <ul className="grid gap-2">
                {visibleDropped.map((scene) => {
                  const detail = droppedReasonLabels[scene.reason];
                  return (
                    <VerificationSceneCard
                      detail={detail}
                      key={scene.sceneId}
                      onPlay={() => setPreviewScene({ scene, detail })}
                      scene={scene}
                      tone="dropped"
                    />
                  );
                })}
              </ul>
              {result.droppedScenes.length > visibleDropped.length ? (
                <p className="text-xs text-(--muted)">
                  빠진 장면 외 {result.droppedScenes.length - visibleDropped.length}개
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {!result && !verification.isPending ? (
        <p className="mt-4 text-xs text-(--muted-2)">
          먼저 후보 검증을 실행하면 확정할 수 있습니다.
        </p>
      ) : null}
      <button
        className={`${styles.primaryButton} mt-4 w-full`}
        disabled={!result || confirmation.isPending || !result.executionId}
        onClick={() => setConfirmOpen(true)}
        type="button"
      >
        {confirmation.isPending ? '확정 중…' : '교정 확정'}
      </button>

      {confirmation.isSuccess ? (
        <p className="mt-4 text-sm font-semibold text-(--positive)" role="status">
          교정을 확정하고 문의를 종료했습니다.
        </p>
      ) : null}
      {confirmation.isError ? (
        <div className="mt-4 space-y-3">
          <ApiErrorNotice
            error={confirmation.error}
            message={
              confirmGuidance ?? '교정 확정을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
            }
          />
        </div>
      ) : null}

      {confirmOpen && result ? (
        <div
          aria-labelledby="confirm-correction-title"
          aria-modal="true"
          className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setConfirmOpen(false);
          }}
          role="dialog"
        >
          <div className="w-full max-w-sm rounded-2xl border border-(--line) bg-(--surface) p-6 shadow-xl">
            <h3 className="font-bold" id="confirm-correction-title">
              교정을 확정할까요?
            </h3>
            <p className="mt-2 text-sm text-(--muted)">
              이 검증 결과를 근거로 교정을 확정하고 문의를 종료합니다. 확정 후에는 되돌릴 수
              없습니다.
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                className={styles.secondaryButton}
                onClick={() => setConfirmOpen(false)}
                type="button"
              >
                취소
              </button>
              <button
                className={styles.primaryButton}
                disabled={confirmation.isPending}
                onClick={() => {
                  confirmation.mutate(result.executionId);
                  setConfirmOpen(false);
                }}
                type="button"
              >
                {confirmation.isPending ? '확정 중…' : '확정'}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {previewScene ? (
        <ScenePreviewDialog
          autoPlay
          contextLabel="교정 검증 결과 · 변경된 장면"
          notice="검증 재검색에서 새로 들어오거나 빠진 장면입니다."
          onClose={() => setPreviewScene(null)}
          result={{
            id: previewScene.scene.sceneId,
            clipId: previewScene.scene.clipId,
            title: previewScene.scene.displayName ?? '제목 없는 영상',
            sceneStart: previewScene.scene.startTimeMs / 1000,
            sceneEnd: previewScene.scene.endTimeMs / 1000,
            duration: formatSceneDuration(
              (previewScene.scene.endTimeMs - previewScene.scene.startTimeMs) / 1000,
            ),
            evidenceType: '검증 결과',
            evidence: previewScene.detail,
            source: previewScene.scene.sceneDescription ?? '장면 설명 없음',
            imageClass: '',
            imageLabel: '',
          }}
          theme={theme}
        />
      ) : null}
    </section>
  );
}
