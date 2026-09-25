'use client';

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { Undo2 } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { releaseReviewInquiry } from '@/features/wireframes/review-inquiry-api';
import { getReleaseErrorMessage } from '@/features/wireframes/review-claim-release-view';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import styles from '@/features/wireframes/review-inquiry-detail.module.css';

interface ClaimReleaseControlProps {
  feedbackId: string;
}

// 담당 검수자가 선점을 풀어 다른 검수자가 맡을 수 있게 한다 (S15P21A501-289). 서버가 대기 중인
// 교정 후보를 모두 폐기하므로 확인 단계를 거친다. 성공하면 상세가 OPEN 으로 다시 그려지면서
// 교정 편집기·태그 초안이 상태 key 로 새로 마운트된다.
export function ClaimReleaseControl({ feedbackId }: ClaimReleaseControlProps) {
  const queryClient = useQueryClient();
  const { showSuccess } = useSuccessToast();
  const [confirmOpen, setConfirmOpen] = useState(false);
  // 교정 저장·태그 후보 변경이 진행 중이면 취소가 그 요청과 엇갈리지 않도록 막는다(편집기·태그와 공유하는 키).
  const parseSavePending = useIsMutating({ mutationKey: ['parse-patch-save', feedbackId] }) > 0;
  const tagChangePending = useIsMutating({ mutationKey: ['tag-candidate-change', feedbackId] }) > 0;
  const release = useMutation({
    mutationFn: () => releaseReviewInquiry(feedbackId),
    retry: false,
    onSuccess: async () => {
      showSuccess('검수를 취소했습니다.');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
      ]);
    },
  });
  const disabled = release.isPending || parseSavePending || tagChangePending;

  return (
    <section className="mt-5 space-y-3" aria-label="검수 취소">
      <button
        className={`${styles.secondaryButton} w-full`}
        disabled={disabled}
        onClick={() => {
          release.reset();
          setConfirmOpen(true);
        }}
        type="button"
      >
        <Undo2 aria-hidden="true" /> {release.isPending ? '검수 취소 중…' : '검수 취소'}
      </button>
      {release.isError ? (
        <ApiErrorNotice error={release.error} message={getReleaseErrorMessage(release.error)} />
      ) : null}

      {confirmOpen ? (
        <div
          aria-labelledby="release-claim-title"
          aria-modal="true"
          className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
          onKeyDown={(event) => {
            if (event.key === 'Escape') setConfirmOpen(false);
          }}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setConfirmOpen(false);
          }}
          role="dialog"
        >
          <div className="w-full max-w-sm rounded-2xl border border-(--line) bg-(--surface) p-6 shadow-xl">
            <h3 className="font-bold" id="release-claim-title">
              검수를 취소할까요?
            </h3>
            <p className="mt-2 text-sm text-(--muted)">
              검수를 취소하면 작성한 교정 후보가 모두 폐기되고, 다른 검수자가 이 문의를 맡을 수
              있습니다.
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                autoFocus
                className={styles.secondaryButton}
                onClick={() => setConfirmOpen(false)}
                type="button"
              >
                돌아가기
              </button>
              <button
                className={styles.primaryButton}
                disabled={disabled}
                onClick={() => {
                  release.mutate();
                  setConfirmOpen(false);
                }}
                type="button"
              >
                검수 취소
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
