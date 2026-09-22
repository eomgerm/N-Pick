'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { type InquiryResolution } from '@/features/wireframes/inquiry-state';
import {
  ResolutionToggle,
  type ResolutionToggleMode,
} from '@/features/wireframes/review-resolution-toggle';
import {
  resolveReviewInquiry,
  type ReviewInquiryDetail,
} from '@/features/wireframes/review-inquiry-api';
import styles from '@/features/wireframes/review-inquiry-detail.module.css';

interface InquiryResolutionFormProps {
  inquiry: ReviewInquiryDetail;
  memberLoginId: string;
}

export function InquiryResolutionForm({ inquiry, memberLoginId }: InquiryResolutionFormProps) {
  const queryClient = useQueryClient();
  const persisted = inquiry.resolution;
  const [mode, setMode] = useState<ResolutionToggleMode>(
    persisted === 'correction' ? 'correction' : 'no_action',
  );
  const [note, setNote] = useState(inquiry.resolutionNote ?? '');
  const [validationError, setValidationError] = useState('');
  const isOwner = inquiry.history.reviewerLoginId === memberLoginId;

  const mutation = useMutation({
    mutationFn: (resolution: InquiryResolution) =>
      resolveReviewInquiry(inquiry.feedbackId, resolution, note),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] }),
      ]);
    },
  });

  // 교정으로 바꾸면 바로 판정을 저장해 아래 교정 편집을 연다 — 별도 저장 버튼·사유 입력이 필요 없다.
  // 오류없음은 사유가 필수이므로 명시적으로 문의를 종료한다.
  function selectMode(next: ResolutionToggleMode) {
    setMode(next);
    setValidationError('');
    mutation.reset();
    if (next === 'correction' && persisted !== 'correction') {
      mutation.mutate('correction');
    }
  }

  function closeAsNoAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!note.trim()) {
      setValidationError('오류 없음으로 종료하려면 처리 사유를 입력해 주세요.');
      return;
    }
    setValidationError('');
    mutation.mutate('no_action');
  }

  if (!isOwner) {
    return (
      <section className={styles.resolutionForm}>
        <h2 className="font-bold">
          {inquiry.history.reviewerLoginId
            ? '다른 아카이브 팀이 처리 중입니다.'
            : '담당자 정보를 확인할 수 없습니다.'}
        </h2>
        <p className="mt-2 text-sm text-(--muted)">담당자만 판정을 저장할 수 있습니다.</p>
      </section>
    );
  }

  return (
    <section className={styles.resolutionForm}>
      <h2 className="font-bold">처리 판정</h2>
      <div className="mt-4 grid gap-4">
        <ResolutionToggle disabled={mutation.isPending} mode={mode} onChange={selectMode} />
        {mode === 'no_action' ? (
          <form className="grid gap-4" onSubmit={closeAsNoAction}>
            <label className="grid gap-2 text-sm font-bold">
              처리 사유
              <textarea
                aria-describedby={validationError ? 'resolution-note-error' : undefined}
                aria-invalid={Boolean(validationError)}
                className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                disabled={mutation.isPending}
                maxLength={2000}
                onChange={(event) => {
                  setNote(event.target.value);
                  setValidationError('');
                  mutation.reset();
                }}
                placeholder="확인한 내용과 판단 이유를 남겨 주세요."
                rows={4}
                value={note}
              />
            </label>
            {validationError ? (
              <p className="text-sm text-(--danger)" id="resolution-note-error" role="alert">
                {validationError}
              </p>
            ) : null}
            {mutation.isError ? <ApiErrorNotice error={mutation.error} /> : null}
            <button className={styles.primaryButton} disabled={mutation.isPending} type="submit">
              {mutation.isPending ? '종료 중…' : '문의 종료'}
            </button>
          </form>
        ) : (
          <>
            <p className="text-sm text-(--muted)">
              아래에서 태그·검색 해석·장면 제외 교정을 담고, 검증한 뒤 교정을 확정하면 문의가
              종료됩니다.
            </p>
            {mutation.isError ? <ApiErrorNotice error={mutation.error} /> : null}
          </>
        )}
      </div>
    </section>
  );
}
