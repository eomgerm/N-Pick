import { Fragment, createElement, type ReactNode } from 'react';

import {
  isCorrectionMode,
  type ResolutionToggleMode,
} from '@/features/wireframes/review-resolution-toggle-mode';

interface CorrectionEditorAreaProps {
  children: ReactNode;
  mode: ResolutionToggleMode;
}

/** 같은 토글 mode를 공유하는 실제 교정 편집·검증 영역의 표시 경계. */
export function CorrectionEditorArea({ children, mode }: CorrectionEditorAreaProps) {
  return isCorrectionMode(mode) ? createElement(Fragment, null, children) : null;
}
