'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  inquiryResolutionLabels,
  type InquiryResolution,
} from '@/features/wireframes/inquiry-state';
import {
  resolveReviewInquiry,
  type ReviewInquiryDetail,
} from '@/features/wireframes/review-inquiry-api';
import styles from '@/features/wireframes/reviewer.module.css';

interface InquiryResolutionFormProps {
  inquiry: ReviewInquiryDetail;
  memberLoginId: string;
}

export function InquiryResolutionForm({ inquiry, memberLoginId }: InquiryResolutionFormProps) {
  const queryClient = useQueryClient();
  const [resolution, setResolution] = useState<InquiryResolution>(
    inquiry.resolution ?? 'no_action',
  );
  const [note, setNote] = useState(inquiry.resolutionNote ?? '');
  const [validationError, setValidationError] = useState('');
  const isOwner = inquiry.history.reviewerLoginId === memberLoginId;
  const isTerminal = resolution === 'no_action' || resolution === 'deferred';
  const mutation = useMutation({
    mutationFn: () => resolveReviewInquiry(inquiry.feedbackId, resolution, note),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', inquiry.feedbackId] }),
      ]);
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedNote = note.trim();
    if (isTerminal && !trimmedNote) {
      setValidationError('조치 없이 종료하거나 보류할 때는 처리 사유를 입력해 주세요.');
      return;
    }
    setValidationError('');
    mutation.mutate();
  }

  if (!isOwner) {
    return (
      <section className="rounded-2xl border border-(--line) bg-(--surface-muted) p-5">
        <h2 className="font-bold">다른 검수자가 처리 중입니다.</h2>
        <p className="mt-2 text-sm text-(--muted)">담당자만 판정을 저장할 수 있습니다.</p>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-(--line) p-5">
      <h2 className="font-bold">처리 판정</h2>
      <form className="mt-4 grid gap-4" onSubmit={handleSubmit}>
        <label className="grid gap-2 text-sm font-bold">
          처리 결과
          <select
            className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
            disabled={mutation.isPending}
            onChange={(event) => {
              setResolution(event.target.value as InquiryResolution);
              setValidationError('');
              mutation.reset();
            }}
            value={resolution}
          >
            {Object.entries(inquiryResolutionLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-2 text-sm font-bold">
          처리 사유 {isTerminal ? '(필수)' : '(선택)'}
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
            rows={4}
            value={note}
          />
        </label>
        {validationError ? (
          <p className="text-sm text-(--danger)" id="resolution-note-error" role="alert">
            {validationError}
          </p>
        ) : null}
        {!isTerminal ? (
          <p className="text-sm text-(--muted)">
            교정 판정은 저장돼도 문의가 종료되지 않습니다. 후속 교정·검증 API가 완료될 때까지 검수
            중으로 유지됩니다.
          </p>
        ) : null}
        {mutation.isError ? <ApiErrorNotice error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="text-sm text-(--positive)" role="status">
            판정을 저장했습니다.
          </p>
        ) : null}
        <button className={styles.primaryButton} disabled={mutation.isPending} type="submit">
          {mutation.isPending ? '저장 중…' : isTerminal ? '문의 종료' : '판정 저장'}
        </button>
      </form>
    </section>
  );
}
