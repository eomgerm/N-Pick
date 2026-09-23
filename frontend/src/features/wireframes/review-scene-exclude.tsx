'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';

import type { ReviewInquiryDetail } from '@/features/wireframes/review-inquiry-api';
import {
  createSceneExcludeCandidate,
  getSceneExcludeMessage,
} from '@/features/wireframes/review-scene-exclude-api';
import { formatInquiryTimecode } from '@/features/wireframes/review-inquiry-view';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import styles from '@/features/wireframes/reviewer.module.css';
import { createIdempotencyKey } from '@/lib/api/idempotency';

interface SceneExcludeCandidateFormProps {
  inquiry: ReviewInquiryDetail;
}

export function SceneExcludeCandidateForm({ inquiry }: SceneExcludeCandidateFormProps) {
  const queryClient = useQueryClient();
  const { showSuccess } = useSuccessToast();
  // 재시도는 같은 키로 보낸다. 멱등 단위는 (feedbackId, targetSceneId)다.
  const idempotencyKey = useRef<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => {
      idempotencyKey.current ??= createIdempotencyKey();
      return createSceneExcludeCandidate(
        inquiry.feedbackId,
        inquiry.sceneId,
        idempotencyKey.current,
      );
    },
    onSuccess: () => {
      showSuccess('제외 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');
      return queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] });
    },
  });

  return (
    <section
      className="rounded-2xl border border-(--line) p-5"
      aria-labelledby="scene-exclude-title"
    >
      <h2 className="font-bold" id="scene-exclude-title">
        장면 제외 후보
      </h2>
      <p className="mt-2 text-sm text-(--muted)">
        신고된 장면만 제외 후보로 저장합니다. 다른 검색 조건이나 다른 장면으로 넓히지 않으며, 검증과
        확정을 거쳐야 검색에 반영됩니다.
      </p>
      <dl className="mt-4 grid gap-3 text-sm md:grid-cols-2">
        <div>
          <dt className="text-(--muted)">대상 장면 ID</dt>
          <dd>{inquiry.sceneId}</dd>
        </div>
        <div>
          <dt className="text-(--muted)">구간</dt>
          <dd>
            {formatInquiryTimecode(inquiry.scene.startTimeMs)}–
            {formatInquiryTimecode(inquiry.scene.endTimeMs)}
          </dd>
        </div>
      </dl>
      {/* 진행 안내는 라이브 영역을 항상 마운트해 두고 텍스트만 토글한다(성공은 토스트로 분리). */}
      <p aria-live="polite" className="mt-4 text-sm" role="status">
        {mutation.isPending ? '제외 후보를 저장하는 중입니다.' : ''}
      </p>
      {mutation.isError ? (
        <p className="mt-2 text-sm text-(--danger)" role="alert">
          {getSceneExcludeMessage(mutation.error)}
        </p>
      ) : null}
      <button
        className={`${styles.primaryButton} mt-4`}
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
        type="button"
      >
        {mutation.isPending ? '저장 중…' : '제외 후보 저장'}
      </button>
    </section>
  );
}
