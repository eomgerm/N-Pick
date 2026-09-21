'use client';

import { useId } from 'react';

import { setParallaxEnabled, useParallaxPreference } from '@/lib/parallax-preference';

export function ParallaxToggle() {
  const descriptionId = useId();
  const { isInitialized, isMotionEnabled, isReducedMotion } = useParallaxPreference();

  return (
    <div className="flex max-w-full min-w-0 flex-col items-end gap-1">
      <button
        aria-checked={isMotionEnabled}
        aria-describedby={isReducedMotion ? descriptionId : undefined}
        aria-label="배경 움직임"
        className="inline-flex min-h-11 shrink-0 items-center gap-2.5 rounded-full bg-white/70 px-3.5 text-xs text-slate-800 backdrop-blur-xl focus-visible:outline-3 focus-visible:outline-offset-4 focus-visible:outline-[#0756c6] hover:enabled:bg-white/90 disabled:cursor-not-allowed"
        disabled={!isInitialized || isReducedMotion}
        onClick={() => setParallaxEnabled(!isMotionEnabled)}
        role="switch"
        type="button"
      >
        <span>배경 움직임</span>
        <span aria-hidden="true">{isMotionEnabled ? '켜짐' : '꺼짐'}</span>
        <span
          aria-hidden="true"
          className={`inline-flex h-5 w-9 shrink-0 items-center rounded-full p-0.5 ${isMotionEnabled ? 'bg-[#0756c6]' : 'bg-slate-500'}`}
        >
          <span
            className={`h-4 w-4 rounded-full bg-white ${isMotionEnabled ? 'translate-x-4' : ''}`}
          />
        </span>
      </button>
      {isReducedMotion && (
        <span className="text-right text-[11px] text-slate-700" id={descriptionId}>
          기기의 모션 감소 설정이 적용 중입니다.
        </span>
      )}
    </div>
  );
}
