'use client';

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { Undo2 } from 'lucide-react';
import { type KeyboardEvent, useEffect, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { releaseReviewInquiry } from '@/features/wireframes/review-inquiry-api';
import {
  isCorrectionMutation,
  nextTrappedFocusIndex,
  releaseErrorMessages,
} from '@/features/wireframes/review-claim-release-view';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import { ApiClientError } from '@/lib/api/error';
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
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreFocus = useRef(false);
  // 교정·판정을 바꾸는 요청이 진행 중이면 취소가 그 요청과 엇갈리지 않도록 막는다.
  const correctionPending =
    useIsMutating({
      predicate: (mutation) => isCorrectionMutation(mutation.options.mutationKey, feedbackId),
    }) > 0;
  const release = useMutation({
    mutationFn: () => releaseReviewInquiry(feedbackId),
    retry: false,
    onSuccess: () => showSuccess('검수를 취소했습니다.'),
    // 실패해도 다시 불러온다 — 응답을 잃고 재시도해 409 를 받은 경우 등 이미 풀린 상태로 화면을 맞춘다.
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
      ]);
    },
  });
  const disabled = release.isPending || correctionPending;

  // 확인창을 닫으면(돌아가기·Escape·바깥 클릭) 연 버튼으로 포커스를 돌려준다.
  useEffect(() => {
    if (confirmOpen || !restoreFocus.current) return;
    restoreFocus.current = false;
    triggerRef.current?.focus();
  }, [confirmOpen]);

  function closeDialog() {
    restoreFocus.current = true;
    setConfirmOpen(false);
  }

  // aria-modal 확인창 밖으로 Tab 이 새지 않도록 안의 버튼끼리만 돈다.
  function handleDialogKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDialog();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = Array.from(
      dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not([disabled])') ?? [],
    );
    const next = nextTrappedFocusIndex(
      focusable.indexOf(document.activeElement as HTMLButtonElement),
      focusable.length,
      event.shiftKey,
    );
    event.preventDefault();
    focusable[next]?.focus();
  }
  const errorMessage =
    release.error instanceof ApiClientError ? releaseErrorMessages[release.error.code] : undefined;

  return (
    <section className="mt-5 space-y-3" aria-label="검수 취소">
      <button
        className={`${styles.secondaryButton} w-full`}
        disabled={disabled}
        ref={triggerRef}
        onClick={() => {
          release.reset();
          setConfirmOpen(true);
        }}
        type="button"
      >
        <Undo2 aria-hidden="true" /> {release.isPending ? '검수 취소 중…' : '검수 취소'}
      </button>
      {release.isError ? <ApiErrorNotice error={release.error} message={errorMessage} /> : null}

      {confirmOpen ? (
        <div
          aria-labelledby="release-claim-title"
          aria-modal="true"
          className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
          onKeyDown={handleDialogKeyDown}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeDialog();
          }}
          ref={dialogRef}
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
                onClick={closeDialog}
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
