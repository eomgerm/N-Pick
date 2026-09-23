'use client';

import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  createTagCorrectionCandidate,
  discardTagCorrectionCandidate,
  reviewTagTypes,
  type ReviewInquiryDetail,
  type ReviewTagScope,
  type ReviewTagType,
  type TagCorrectionOperation,
} from '@/features/wireframes/review-inquiry-api';
import { evidenceLabel } from '@/features/wireframes/review-inquiry-view';
import { resolutionModeFromValue } from '@/features/wireframes/review-resolution-toggle-mode';
import { useSuccessToast } from '@/features/wireframes/success-toast';
import { createIdempotencyKey } from '@/lib/api/idempotency';

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
  SCENE: '이 장면',
  CLIP: '영상 전체',
};

const MAX_TAG_DRAFTS = 10;

const addButtonClass =
  'rounded-lg border border-(--line) bg-(--surface) px-3 py-1.5 text-sm font-bold text-(--accent-strong) transition-colors hover:border-(--accent) disabled:cursor-not-allowed disabled:opacity-40';
const pillClass =
  'inline-flex w-fit max-w-full items-center gap-2 justify-self-start rounded-full bg-(--accent-soft) py-1.5 pr-2 pl-3 text-sm text-(--accent-strong)';
const iconButtonClass =
  'grid size-5 shrink-0 place-items-center rounded-full text-base leading-none text-(--muted)';

type Evidence = ReviewInquiryDetail['evidence'][number];

interface Draft {
  id: string;
  scope: ReviewTagScope;
  tagType: ReviewTagType;
  value: string;
  error: string;
}

interface AddedTag {
  id: string;
  scope: ReviewTagScope;
  tagType: ReviewTagType;
  value: string;
}

interface CandidateSubmission {
  operations: TagCorrectionOperation[];
  draftId?: string;
  added?: AddedTag;
  removedTaggingId?: string;
  restoredTaggingId?: string;
}

interface ReviewInquiryTagsProps {
  inquiry: ReviewInquiryDetail;
  memberLoginId: string;
}

function validateTagValue(value: string, tagType: ReviewTagType): string {
  const trimmed = value.trim();
  if (!trimmed) return '태그 값을 입력해 주세요.';
  if (trimmed.length > 20) return '태그는 20자 이하여야 합니다.';
  if (
    (tagType === 'filmed_date' || tagType === 'broadcast_date') &&
    !/^\d{4}-\d{2}-\d{2}$/.test(trimmed)
  ) {
    return '날짜 태그는 YYYY-MM-DD 형식이어야 합니다.';
  }
  return '';
}

export function ReviewInquiryTags({ inquiry, memberLoginId }: ReviewInquiryTagsProps) {
  const { showSuccess } = useSuccessToast();
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [added, setAdded] = useState<AddedTag[]>([]);
  const [removed, setRemoved] = useState<Set<string>>(new Set());
  const seq = useRef(0);
  const isOwner = inquiry.history.reviewerLoginId === memberLoginId;
  const canCorrect =
    inquiry.status === 'reviewing' &&
    isOwner &&
    resolutionModeFromValue(inquiry.resolution) === 'correction';
  const mutation = useMutation({
    mutationFn: ({ operations }: CandidateSubmission) =>
      createTagCorrectionCandidate(inquiry.feedbackId, operations, createIdempotencyKey()),
    // 서버 반영이 성공한 뒤에만 로컬 상태를 바꾼다 — 실패 시 UI 와 서버가 어긋나지 않는다.
    onSuccess: (_response, submission) => {
      if (submission.draftId) {
        setDrafts((current) => current.filter((draft) => draft.id !== submission.draftId));
      }
      if (submission.added) {
        const next = submission.added;
        setAdded((current) => [...current, next]);
      }
      if (submission.removedTaggingId) {
        const id = submission.removedTaggingId;
        setRemoved((current) => new Set(current).add(id));
      }
      if (submission.restoredTaggingId) {
        const id = submission.restoredTaggingId;
        setRemoved((current) => {
          const next = new Set(current);
          next.delete(id);
          return next;
        });
      }
      showSuccess('태그 교정 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');
    },
  });

  // 추가 취소: 후보 하나만 지우는 서버 API 가 없어, 이 신고의 대기 태그 후보를 모두 폐기하고 남은 것만
  // 한 번에 다시 올린다. WITHDRAW 를 더 쌓지 않으므로 서버 후보 수가 실제로 줄고 50개 상한에 걸리지
  // 않는다 (S15P21A501-309). 폐기가 실패하면 로컬 상태를 바꾸지 않아 UI 와 서버가 어긋나지 않는다.
  const cancelAdded = useMutation({
    mutationFn: async (tag: AddedTag) => {
      const nextAdded = added.filter((item) => item.id !== tag.id);
      await discardTagCorrectionCandidate(inquiry.feedbackId);
      const operations: TagCorrectionOperation[] = [
        ...nextAdded.map((item) => ({
          action: 'APPROVE' as const,
          scope: item.scope,
          tagType: item.tagType,
          matchValue: item.value,
          displayName: item.value,
        })),
        ...inquiry.evidence
          .filter((evidence) => removed.has(evidence.taggingId))
          .map((evidence) => ({
            action: 'REJECT' as const,
            scope: evidence.scope,
            tagType: evidence.tagType,
            matchValue: evidence.matchValue,
            displayName: evidence.tagName,
          })),
      ];
      if (operations.length > 0) {
        await createTagCorrectionCandidate(inquiry.feedbackId, operations, createIdempotencyKey());
      }
      return nextAdded;
    },
    onSuccess: (nextAdded) => setAdded(nextAdded),
  });

  const pendingCount = drafts.length + added.length;

  function addDraft(scope: ReviewTagScope) {
    if (pendingCount >= MAX_TAG_DRAFTS) return;
    seq.current += 1;
    setDrafts((current) => [
      ...current,
      { id: `draft-${seq.current}`, scope, tagType: 'keyword', value: '', error: '' },
    ]);
    mutation.reset();
  }

  function updateDraft(id: string, patch: Partial<Draft>) {
    setDrafts((current) =>
      current.map((draft) => (draft.id === id ? { ...draft, ...patch, error: '' } : draft)),
    );
    mutation.reset();
  }

  function removeDraft(id: string) {
    setDrafts((current) => current.filter((draft) => draft.id !== id));
  }

  // ✓: 검증 후보를 만들고, 편집 칩을 일반 태그와 같은 형태의 칩으로 남긴다.
  function submitDraft(draft: Draft) {
    const error = validateTagValue(draft.value, draft.tagType);
    if (error) {
      setDrafts((current) =>
        current.map((item) => (item.id === draft.id ? { ...item, error } : item)),
      );
      return;
    }
    const value = draft.value.trim();
    mutation.mutate({
      operations: [
        {
          action: 'APPROVE',
          scope: draft.scope,
          tagType: draft.tagType,
          matchValue: value,
          displayName: value,
        },
      ],
      draftId: draft.id,
      added: { id: draft.id, scope: draft.scope, tagType: draft.tagType, value },
    });
  }

  function removeAdded(tag: AddedTag) {
    cancelAdded.reset();
    cancelAdded.mutate(tag);
  }

  function markRemoved(evidence: Evidence) {
    mutation.reset();
    mutation.mutate({
      operations: [
        {
          action: 'REJECT',
          scope: evidence.scope,
          tagType: evidence.tagType,
          matchValue: evidence.matchValue,
          displayName: evidence.tagName,
        },
      ],
      removedTaggingId: evidence.taggingId,
    });
  }

  function restoreRemoved(evidence: Evidence) {
    mutation.reset();
    mutation.mutate({
      operations: [
        {
          action: 'APPROVE',
          scope: evidence.scope,
          tagType: evidence.tagType,
          matchValue: evidence.matchValue,
          displayName: evidence.tagName,
        },
      ],
      restoredTaggingId: evidence.taggingId,
    });
  }

  const activeTags = inquiry.evidence.filter((evidence) => !removed.has(evidence.taggingId));
  const removedTags = inquiry.evidence.filter((evidence) => removed.has(evidence.taggingId));
  const hasTop = activeTags.length > 0 || added.length > 0 || drafts.length > 0;

  return (
    <section className="rounded-2xl border border-(--line) p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-bold">태그 교정</h3>
        {canCorrect ? (
          <div className="flex flex-wrap gap-2">
            <button
              className={addButtonClass}
              disabled={mutation.isPending || pendingCount >= MAX_TAG_DRAFTS}
              onClick={() => addDraft('SCENE')}
              type="button"
            >
              + 이 장면
            </button>
            <button
              className={addButtonClass}
              disabled={mutation.isPending || pendingCount >= MAX_TAG_DRAFTS}
              onClick={() => addDraft('CLIP')}
              type="button"
            >
              + 영상 전체
            </button>
          </div>
        ) : null}
      </div>

      {!hasTop ? (
        <p className="mt-3 text-sm text-(--muted)">현재 표시할 태그가 없습니다.</p>
      ) : (
        <ul className="mt-4 grid gap-2" aria-label="현재 장면과 영상의 태그">
          {activeTags.map((evidence, index) => (
            <li
              className={pillClass}
              key={`${evidence.taggingId}-${index}`}
              title={`출처: ${evidence.sources.map(evidenceLabel).join('·') || '기록 없음'} · 검증: ${evidenceLabel(
                evidence.verifiedState,
              )} · 범위: ${tagScopeLabels[evidence.scope]}`}
            >
              <strong className="truncate font-semibold">{evidence.tagName}</strong>
              <span className="shrink-0 text-xs text-(--muted)">
                {tagTypeLabels[evidence.tagType]}
              </span>
              {canCorrect ? (
                <button
                  aria-label={`‘${evidence.tagName}’ 삭제 후보`}
                  className={`${iconButtonClass} hover:text-(--danger)`}
                  disabled={mutation.isPending}
                  onClick={() => markRemoved(evidence)}
                  type="button"
                >
                  ×
                </button>
              ) : null}
            </li>
          ))}

          {added.map((tag) => (
            <li className={pillClass} key={tag.id}>
              <strong className="truncate font-semibold">{tag.value}</strong>
              <span className="shrink-0 text-xs text-(--muted)">
                {tagTypeLabels[tag.tagType]} · {tagScopeLabels[tag.scope]}
              </span>
              <button
                aria-label={`‘${tag.value}’ 추가 취소`}
                className={`${iconButtonClass} hover:text-(--danger)`}
                disabled={mutation.isPending || cancelAdded.isPending}
                onClick={() => removeAdded(tag)}
                type="button"
              >
                ×
              </button>
            </li>
          ))}

          {canCorrect
            ? drafts.map((draft) => (
                <li
                  className={`${pillClass} border border-dashed border-(--accent) bg-(--accent-soft)`}
                  key={draft.id}
                >
                  <span className="shrink-0 text-xs font-bold text-(--accent-strong)">
                    {tagScopeLabels[draft.scope]}
                  </span>
                  <select
                    aria-label="태그 유형"
                    className="bg-transparent text-xs font-bold text-(--accent-strong) outline-none"
                    disabled={mutation.isPending}
                    onChange={(event) =>
                      updateDraft(draft.id, { tagType: event.target.value as ReviewTagType })
                    }
                    value={draft.tagType}
                  >
                    {reviewTagTypes.map((value) => (
                      <option key={value} value={value}>
                        {tagTypeLabels[value]}
                      </option>
                    ))}
                  </select>
                  <input
                    aria-label="태그 값"
                    autoFocus
                    className="w-24 bg-transparent text-sm font-semibold text-(--accent-strong) outline-none placeholder:text-(--muted)"
                    disabled={mutation.isPending}
                    maxLength={20}
                    onChange={(event) => updateDraft(draft.id, { value: event.target.value })}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        submitDraft(draft);
                      }
                      if (event.key === 'Escape') removeDraft(draft.id);
                    }}
                    placeholder={
                      draft.tagType === 'filmed_date' || draft.tagType === 'broadcast_date'
                        ? '2026-09-21'
                        : '값 입력'
                    }
                    value={draft.value}
                  />
                  <button
                    aria-label="태그 추가 확정"
                    className={`${iconButtonClass} hover:text-(--positive)`}
                    disabled={mutation.isPending}
                    onClick={() => submitDraft(draft)}
                    type="button"
                  >
                    ✓
                  </button>
                  <button
                    aria-label="태그 추가 취소"
                    className={`${iconButtonClass} hover:text-(--danger)`}
                    disabled={mutation.isPending}
                    onClick={() => removeDraft(draft.id)}
                    type="button"
                  >
                    ×
                  </button>
                </li>
              ))
            : null}
        </ul>
      )}

      {removedTags.length > 0 ? (
        <div className="mt-5 border-t border-(--line) pt-5">
          <h4 className="text-sm font-bold text-(--muted)">삭제 후보</h4>
          <ul className="mt-3 grid gap-2">
            {removedTags.map((evidence, index) => (
              <li
                className="inline-flex w-fit max-w-full items-center gap-2 justify-self-start rounded-full border border-dashed border-(--line) py-1.5 pr-2 pl-3 text-sm text-(--muted) line-through"
                key={`removed-${evidence.taggingId}-${index}`}
              >
                <strong className="truncate font-semibold">{evidence.tagName}</strong>
                <span className="shrink-0 text-xs no-underline">
                  {tagTypeLabels[evidence.tagType]}
                </span>
                <button
                  aria-label={`‘${evidence.tagName}’ 삭제 취소`}
                  className={`${iconButtonClass} no-underline hover:text-(--accent-strong)`}
                  disabled={mutation.isPending}
                  onClick={() => restoreRemoved(evidence)}
                  type="button"
                >
                  +
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {drafts.some((draft) => draft.error) ? (
        <ul className="mt-2 grid gap-1">
          {drafts
            .filter((draft) => draft.error)
            .map((draft) => (
              <li className="text-sm text-(--danger)" key={draft.id} role="alert">
                {draft.error}
              </li>
            ))}
        </ul>
      ) : null}

      {mutation.isError ? (
        <div className="mt-3">
          <ApiErrorNotice error={mutation.error} />
        </div>
      ) : null}
    </section>
  );
}
