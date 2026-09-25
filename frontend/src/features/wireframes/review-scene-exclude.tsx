'use client';

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import {
  correctionCandidatesQueryKey,
  type ReviewInquiryDetail,
} from '@/features/wireframes/review-inquiry-api';
import {
  createSceneExcludeCandidate,
  discardSceneExcludeCandidate,
  getSceneExcludeMessage,
} from '@/features/wireframes/review-scene-exclude-api';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import { useCorrectionCandidates } from '@/features/wireframes/use-correction-candidates';
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
  // 서버의 대기 후보가 정본이다 (S15P21A501-317): 새로고침 뒤에도 이 장면을 뺀 후보가 있으면 제외 상태로
  // 연다. 등록·취소 직후의 로컬 값은 다음 서버 응답이 올 때까지만 쓴다.
  const candidates = useCorrectionCandidates(inquiry.feedbackId, true);
  const [localExcluded, setLocalExcluded] = useState<boolean | null>(null);
  const [seenAt, setSeenAt] = useState(0);
  if (candidates.dataUpdatedAt !== seenAt) {
    setSeenAt(candidates.dataUpdatedAt);
    setLocalExcluded(null);
  }
  const excluded =
    localExcluded ??
    candidates.data?.sceneExcludes.some((item) => item.targetSceneId === inquiry.sceneId) ??
    false;
  const idempotencyKey = useRef<string | null>(null);

  // 대기 후보를 아직 못 읽었으면(첫 조회 중·실패) 토글을 잠근다 — 이미 제외했는데 "이 장면 제외"를 다시
  // 누르지 않게 한다. 조회가 성공하면 풀린다.
  const isUnknown = !candidates.isSuccess;

  // 대기 후보 다시 읽기가 끝날 때까지 요청을 진행 중으로 둔다 — 검증이 옛 후보 수로 돌지 않게 한다.
  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] });
    return queryClient.invalidateQueries({
      queryKey: correctionCandidatesQueryKey(inquiry.feedbackId),
    });
  }

  // 후보를 바꾸는 요청은 같은 키를 단다 — 검증 패널이 감시해 진행 중에는 검증을 막는다.
  const changeKey = ['scene-exclude-change', inquiry.feedbackId];
  // 검증 재검색 중에는 후보를 바꾸지 않는다 — 검증에 들어간 후보와 표시한 후보 수가 어긋나지 않게 한다.
  const verifyPending =
    useIsMutating({ mutationKey: ['verification-run', inquiry.feedbackId] }) > 0;

  const register = useMutation({
    mutationKey: changeKey,
    mutationFn: () => {
      idempotencyKey.current ??= createIdempotencyKey();
      return createSceneExcludeCandidate(
        inquiry.feedbackId,
        inquiry.sceneId,
        idempotencyKey.current,
      );
    },
    onSuccess: () => {
      setLocalExcluded(true);
      showSuccess('제외 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');
      return refresh();
    },
  });

  const cancel = useMutation({
    mutationKey: changeKey,
    mutationFn: () => discardSceneExcludeCandidate(inquiry.feedbackId),
    onSuccess: () => {
      setLocalExcluded(false);
      idempotencyKey.current = null;
      showSuccess('장면 제외 후보를 취소했습니다.');
      return refresh();
    },
  });

  const pending = register.isPending || cancel.isPending || verifyPending || isUnknown;
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
      {candidates.isError ? (
        <p className="w-full text-sm text-(--muted)">
          저장해 둔 장면 제외 후보를 불러오지 못했습니다.{' '}
          <button
            className="font-bold text-(--accent-strong) underline"
            onClick={() => void candidates.refetch()}
            type="button"
          >
            다시 불러오기
          </button>
        </p>
      ) : null}
      {error ? (
        <p className="w-full text-sm text-(--danger)" role="alert">
          {getSceneExcludeMessage(error)}
        </p>
      ) : null}
    </div>
  );
}
