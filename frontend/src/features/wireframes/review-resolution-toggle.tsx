'use client';

import { type ReactNode, useId } from 'react';

import {
  isCorrectionMode,
  resolutionModeFromChecked,
  type ResolutionToggleMode,
} from '@/features/wireframes/review-resolution-toggle-mode';

export type { ResolutionToggleMode } from '@/features/wireframes/review-resolution-toggle-mode';
export {
  isCorrectionMode,
  resolutionModeFromChecked,
  resolutionModeFromValue,
} from '@/features/wireframes/review-resolution-toggle-mode';

interface ResolutionToggleProps {
  children?: ReactNode;
  disabled?: boolean;
  mode: ResolutionToggleMode;
  onChange: (next: ResolutionToggleMode) => void;
}

/** 오류없음 ⟷ 교정 토글. ON일 때만 children(교정 편집)을 렌더한다. */
export function ResolutionToggle({ children, disabled, mode, onChange }: ResolutionToggleProps) {
  const checked = isCorrectionMode(mode);
  const labelId = useId();
  return (
    <div className="grid gap-2">
      <span className="text-sm font-bold" id={labelId}>
        처리 결과
      </span>
      <div className="flex items-center gap-3">
        <span className={checked ? 'text-sm text-(--muted)' : 'text-sm font-bold'}>오류없음</span>
        <button
          aria-checked={checked}
          aria-labelledby={labelId}
          className="relative h-7 w-12 shrink-0 rounded-full border border-(--line) bg-(--surface-muted) transition-colors data-[checked=true]:border-(--accent) data-[checked=true]:bg-(--accent)"
          data-checked={checked}
          disabled={disabled}
          onClick={() => onChange(resolutionModeFromChecked(!checked))}
          role="switch"
          type="button"
        >
          <span
            aria-hidden="true"
            className="absolute top-0.5 left-0.5 h-6 w-6 rounded-full bg-(--surface) shadow transition-transform data-[checked=true]:translate-x-5"
            data-checked={checked}
          />
        </button>
        <span className={checked ? 'text-sm font-bold' : 'text-sm text-(--muted)'}>교정</span>
      </div>
      {checked ? children : null}
    </div>
  );
}
