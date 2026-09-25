'use client';

import type { ReviewTagScope, ReviewTagType } from '@/features/wireframes/review-inquiry-api';
import {
  tagTypeEffectGroups,
  tagTypeEffectHint,
  tagTypeLabels,
} from '@/features/wireframes/tag-type-effect';

export const pillClass =
  'inline-flex w-fit max-w-full items-center gap-2 justify-self-start rounded-full bg-(--accent-soft) py-1.5 pr-2 pl-3 text-sm text-(--accent-strong)';
export const iconButtonClass =
  'grid size-5 shrink-0 place-items-center rounded-full text-base leading-none text-(--muted)';

export interface TagDraft {
  id: string;
  scope: ReviewTagScope;
  /** 기본값 없음 — 검수자가 유형별 검색 효과를 보고 직접 고른다 (S15P21A501-317). */
  tagType: ReviewTagType | null;
  value: string;
  error: string;
}

interface TagDraftChipProps {
  draft: TagDraft;
  scopeLabel: string;
  isBusy: boolean;
  onChange: (patch: Partial<TagDraft>) => void;
  onSubmit: () => void;
  onRemove: () => void;
}

export function TagDraftChip({
  draft,
  scopeLabel,
  isBusy,
  onChange,
  onSubmit,
  onRemove,
}: TagDraftChipProps) {
  const hintId = `${draft.id}-type-hint`;
  const isDate = draft.tagType === 'filmed_date' || draft.tagType === 'broadcast_date';
  return (
    <li className="grid gap-1 justify-self-start">
      <div className={`${pillClass} border border-dashed border-(--accent) bg-(--accent-soft)`}>
        <span className="shrink-0 text-xs font-bold text-(--accent-strong)">{scopeLabel}</span>
        <select
          aria-describedby={hintId}
          aria-label="태그 유형"
          autoFocus
          className="bg-transparent text-xs font-bold text-(--accent-strong) outline-none"
          disabled={isBusy}
          onChange={(event) => onChange({ tagType: event.target.value as ReviewTagType })}
          value={draft.tagType ?? ''}
        >
          <option disabled value="">
            유형 선택
          </option>
          {tagTypeEffectGroups.map((group) => (
            <optgroup key={group.effect} label={group.label}>
              {group.types.map((value) => (
                <option key={value} value={value}>
                  {tagTypeLabels[value]}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
        <input
          aria-label="태그 값"
          className="w-24 bg-transparent text-sm font-semibold text-(--accent-strong) outline-none placeholder:text-(--muted)"
          disabled={isBusy}
          maxLength={20}
          onChange={(event) => onChange({ value: event.target.value })}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              onSubmit();
            }
            if (event.key === 'Escape') onRemove();
          }}
          placeholder={isDate ? '2026-09-21' : '값 입력'}
          value={draft.value}
        />
        <button
          aria-describedby={hintId}
          aria-label="태그 추가 확정"
          className={`${iconButtonClass} hover:text-(--positive) disabled:cursor-not-allowed disabled:opacity-40`}
          disabled={isBusy || draft.tagType === null}
          onClick={onSubmit}
          type="button"
        >
          ✓
        </button>
        <button
          aria-label="태그 추가 취소"
          className={`${iconButtonClass} hover:text-(--danger)`}
          disabled={isBusy}
          onClick={onRemove}
          type="button"
        >
          ×
        </button>
      </div>
      <p className="pl-3 text-xs text-(--muted)" id={hintId}>
        {draft.tagType ? tagTypeEffectHint(draft.tagType) : '태그 유형을 먼저 선택해 주세요.'}
      </p>
    </li>
  );
}
