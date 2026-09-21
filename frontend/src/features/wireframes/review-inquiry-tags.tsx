'use client';

import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  createTagCorrectionCandidate,
  parseCommaSeparatedTags,
  reviewTagTypes,
  type ReviewInquiryDetail,
  type ReviewTagScope,
  type ReviewTagType,
  type TagCorrectionOperation,
} from '@/features/wireframes/review-inquiry-api';
import { evidenceLabel } from '@/features/wireframes/review-inquiry-view';
import styles from '@/features/wireframes/reviewer.module.css';

const tagTypeLabels: Record<ReviewTagType, string> = {
  person: '인물',
  organization: '조직',
  location: '장소',
  facility: '시설',
  keyword: '키워드',
  event: '사건',
  season: '계절',
  weather: '날씨',
  scene_type: '장면 유형',
  filmed_date: '촬영일',
  broadcast_date: '방송일',
};

const tagScopeLabels: Record<ReviewTagScope, string> = {
  SCENE: '이 장면만',
  CLIP: '영상 전체',
};

type Evidence = ReviewInquiryDetail['evidence'][number];

interface CandidateSubmission {
  operations: TagCorrectionOperation[];
  successMessage: string;
}

interface ReviewInquiryTagsProps {
  inquiry: ReviewInquiryDetail;
  memberLoginId: string;
}

function validateTagValues(values: string[], tagType: ReviewTagType): string {
  if (values.length === 0) return '쉼표로 구분한 태그를 한 개 이상 입력해 주세요.';
  if (values.some((value) => value.length > 255)) return '각 태그는 255자 이하여야 합니다.';
  if (
    (tagType === 'filmed_date' || tagType === 'broadcast_date') &&
    values.some((value) => !/^\d{4}-\d{2}-\d{2}$/.test(value))
  ) {
    return '날짜 태그는 YYYY-MM-DD 형식으로 입력해 주세요.';
  }
  return '';
}

export function ReviewInquiryTags({ inquiry, memberLoginId }: ReviewInquiryTagsProps) {
  const [tagType, setTagType] = useState<ReviewTagType>('keyword');
  const [scope, setScope] = useState<ReviewTagScope>('SCENE');
  const [tagInput, setTagInput] = useState('');
  const [validationError, setValidationError] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<Evidence | null>(null);
  const values = parseCommaSeparatedTags(tagInput);
  const isOwner = inquiry.history.reviewerLoginId === memberLoginId;
  const canCorrect =
    inquiry.status === 'reviewing' &&
    isOwner &&
    (inquiry.resolution === 'tag_correction' || inquiry.resolution === 'patch_parse');
  const mutation = useMutation({
    mutationFn: ({ operations }: CandidateSubmission) =>
      createTagCorrectionCandidate(inquiry.feedbackId, operations),
    onSuccess: (_response, submission) => {
      setValidationError('');
      setDeleteTarget(null);
      if (submission.operations.every((operation) => operation.action === 'APPROVE')) {
        setTagInput('');
      }
    },
  });

  function resetFeedback() {
    setValidationError('');
    mutation.reset();
  }

  function submitAdditions(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const error = validateTagValues(values, tagType);
    if (error) {
      setValidationError(error);
      return;
    }
    setValidationError('');
    mutation.mutate({
      operations: values.map((value) => ({
        action: 'APPROVE',
        scope,
        tagType,
        matchValue: value,
        displayName: value,
      })),
      successMessage: `${values.length}개 태그를 검증 후보로 저장했습니다.`,
    });
  }

  function submitDeletion() {
    if (!deleteTarget) return;
    mutation.mutate({
      operations: [
        {
          action: 'REJECT',
          scope: deleteTarget.scope,
          tagType: deleteTarget.tagType,
          matchValue: deleteTarget.matchValue,
          displayName: deleteTarget.tagName,
        },
      ],
      successMessage: `'${deleteTarget.tagName}' 삭제 후보를 저장했습니다.`,
    });
  }

  return (
    <section className="rounded-2xl border border-(--line) p-5">
      <h3 className="font-bold">현재 태그</h3>
      {inquiry.evidence.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">현재 표시할 태그가 없습니다.</p>
      ) : (
        <ul className="mt-4 grid gap-2" aria-label="현재 장면과 영상의 태그">
          {inquiry.evidence.map((evidence, index) => (
            <li
              className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-(--accent-soft) px-3 py-2 text-sm text-(--accent-strong)"
              key={`${evidence.taggingId}-${index}`}
            >
              <span>
                <strong>{evidence.tagName}</strong>
                <span className="mt-1 block text-xs text-(--muted)">
                  {tagTypeLabels[evidence.tagType]} · 출처:{' '}
                  {evidence.sources.map(evidenceLabel).join('·') || '기록 없음'} · 검증:{' '}
                  {evidenceLabel(evidence.verifiedState)} · 범위: {tagScopeLabels[evidence.scope]}
                </span>
              </span>
              {canCorrect ? (
                <button
                  className={styles.secondaryButton}
                  disabled={mutation.isPending}
                  onClick={() => {
                    resetFeedback();
                    setDeleteTarget(evidence);
                  }}
                  type="button"
                >
                  삭제 후보
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {canCorrect ? (
        <div className="mt-5 border-t border-(--line) pt-5">
          <h4 className="font-bold">태그 추가</h4>
          <p className="mt-1 text-sm text-(--muted)">
            쉼표로 여러 태그를 구분하면 한 번에 각각의 검증 후보를 만듭니다.
          </p>
          <form className="mt-4 grid gap-4" onSubmit={submitAdditions}>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="grid gap-2 text-sm font-bold">
                태그 유형
                <select
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  onChange={(event) => {
                    setTagType(event.target.value as ReviewTagType);
                    resetFeedback();
                  }}
                  value={tagType}
                >
                  {reviewTagTypes.map((value) => (
                    <option key={value} value={value}>
                      {tagTypeLabels[value]}
                    </option>
                  ))}
                </select>
              </label>
              <label className="grid gap-2 text-sm font-bold">
                적용 범위
                <select
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  onChange={(event) => {
                    setScope(event.target.value as ReviewTagScope);
                    resetFeedback();
                  }}
                  value={scope}
                >
                  <option value="SCENE">이 장면만</option>
                  <option value="CLIP">영상 전체</option>
                </select>
              </label>
            </div>
            <label className="grid gap-2 text-sm font-bold">
              태그 값 (쉼표로 구분)
              <input
                aria-describedby={validationError ? 'tag-values-error' : 'tag-values-help'}
                aria-invalid={Boolean(validationError)}
                className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                disabled={mutation.isPending}
                onChange={(event) => {
                  setTagInput(event.target.value);
                  resetFeedback();
                }}
                placeholder={
                  tagType === 'filmed_date' || tagType === 'broadcast_date'
                    ? '2026-09-21, 2026-09-22'
                    : '서울, 부산, 광주'
                }
                value={tagInput}
              />
              <span className="text-xs font-normal text-(--muted)" id="tag-values-help">
                {values.length > 0
                  ? `${values.length}개 후보: ${values.join(' · ')}`
                  : '입력 대기 중'}
              </span>
            </label>
            {validationError ? (
              <p className="text-sm text-(--danger)" id="tag-values-error" role="alert">
                {validationError}
              </p>
            ) : null}
            <button className={styles.primaryButton} disabled={mutation.isPending} type="submit">
              {mutation.isPending ? '후보 저장 중…' : `${values.length || 0}개 추가 후보 만들기`}
            </button>
          </form>

          {deleteTarget ? (
            <div className="mt-4 rounded-2xl border border-(--line) bg-(--warning-soft) p-4">
              <strong>‘{deleteTarget.tagName}’ 태그를 삭제 후보로 만들까요?</strong>
              <p className="mt-1 text-sm text-(--muted)">
                {tagScopeLabels[deleteTarget.scope]} 범위에서 반려합니다. 검증과 확정 전까지 현재
                태그는 바뀌지 않습니다.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  className={styles.primaryButton}
                  disabled={mutation.isPending}
                  onClick={submitDeletion}
                  type="button"
                >
                  {mutation.isPending ? '후보 저장 중…' : '삭제 후보 저장'}
                </button>
                <button
                  className={styles.secondaryButton}
                  disabled={mutation.isPending}
                  onClick={() => setDeleteTarget(null)}
                  type="button"
                >
                  취소
                </button>
              </div>
            </div>
          ) : null}

          {mutation.isError ? <ApiErrorNotice error={mutation.error} /> : null}
          {mutation.isSuccess ? (
            <p className="mt-4 text-sm text-(--positive)" role="status">
              {mutation.variables.successMessage} 다음 단계에서 검증 검색과 확정이 필요합니다.
            </p>
          ) : null}
        </div>
      ) : inquiry.status === 'reviewing' && isOwner ? (
        <p className="mt-4 text-sm text-(--muted)">
          처리 판정을 태그 교정 또는 검색 해석 교정으로 저장하면 태그를 추가하거나 삭제할 수
          있습니다.
        </p>
      ) : null}
    </section>
  );
}
