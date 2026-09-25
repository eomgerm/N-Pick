'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import type { ReviewInquiryDetail } from '@/features/wireframes/review-inquiry-api';
import {
  createSceneExcludeCandidate,
  discardSceneExcludeCandidate,
  getSceneExcludeMessage,
} from '@/features/wireframes/review-scene-exclude-api';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import styles from '@/features/wireframes/reviewer.module.css';
import { createIdempotencyKey } from '@/lib/api/idempotency';

interface SceneExcludeCandidateFormProps {
  inquiry: ReviewInquiryDetail;
}

// 장면 제외는 이 장면을 넣냐/빼냐의 이진 선택이라 별도 폼 없이 토글 하나로 끝낸다.
// 등록은 후보 생성(POST), 취소는 후보 폐기(DELETE). 검증·확정 전까지 자유롭게 되돌린다.
export function SceneExcludeCandidateForm({ inquiry }: SceneExcludeCandidateFormProps) {
  const queryClient = useQueryClient();
  const { showSuccess } = useSuccessToast();
  const [excluded, setExcluded] = useState(false);
  const idempotencyKey = useRef<string | null>(null);

  const register = useMutation({
    mutationKey: ['scene-exclude-change', inquiry.feedbackId],
    mutationFn: () => {
      idempotencyKey.current ??= createIdempotencyKey();
      return createSceneExcludeCandidate(
        inquiry.feedbackId,
        inquiry.sceneId,
        idempotencyKey.current,
      );
    },
    onSuccess: () => {
      setExcluded(true);
      showSuccess('제외 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');
      queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] });
    },
  });

  const cancel = useMutation({
    mutationKey: ['scene-exclude-change', inquiry.feedbackId],
    mutationFn: () => discardSceneExcludeCandidate(inquiry.feedbackId),
    onSuccess: () => {
      setExcluded(false);
      idempotencyKey.current = null;
      showSuccess('장면 제외 후보를 취소했습니다.');
      queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] });
    },
  });

  const pending = register.isPending || cancel.isPending;
  const error = register.error ?? cancel.error;

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-(--line) bg-(--surface) p-4">
      <div className="min-w-0">
        <p className="text-sm font-bold">이 장면 검색에서 제외</p>
        <p className="mt-0.5 text-xs text-(--muted)">
          신고된 장면(#{inquiry.sceneId})을 검색 결과에서 빼는 교정입니다. 검증·확정 후 반영됩니다.
        </p>
      </div>
      {excluded ? (
        <button
          className={styles.secondaryButton}
          disabled={pending}
          onClick={() => cancel.mutate()}
          type="button"
        >
          {cancel.isPending ? '취소 중…' : '제외 취소'}
        </button>
      ) : (
        <button
          className={styles.primaryButton}
          disabled={pending}
          onClick={() => register.mutate()}
          type="button"
        >
          {register.isPending ? '제외 중…' : '이 장면 제외'}
        </button>
      )}
      {error ? (
        <p className="w-full text-sm text-(--danger)" role="alert">
          {getSceneExcludeMessage(error)}
        </p>
      ) : null}
    </div>
  );
}
