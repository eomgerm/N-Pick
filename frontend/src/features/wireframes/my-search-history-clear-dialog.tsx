'use client';

import { TriangleAlert } from 'lucide-react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { SceneDialog } from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

interface MySearchHistoryClearDialogProps {
  totalElements: number;
  error: unknown;
  isClearing: boolean;
  theme: WireframeTheme;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 검색 기록 전체 삭제 확인 (S15P21A501-291).
 *
 * 건별 확인({@link MySearchHistoryDeleteDialog})과 같은 이유로 버튼 한 번에 지우지 않는다 — 되돌릴 수단이 없고 서버의 행 보존은 감사·신고용이지 복구 경로가 아니다.
 * 한 번에 여러 건이 사라지므로 건수를 밝혀 규모를 알린다. 기본 포커스는 취소에 둔다.
 */
export function MySearchHistoryClearDialog({
  totalElements,
  error,
  isClearing,
  theme,
  onCancel,
  onConfirm,
}: MySearchHistoryClearDialogProps) {
  return (
    <SceneDialog
      className={styles.confirmDialog}
      describedBy="search-history-clear-description"
      isLocked={isClearing}
      labelledBy="search-history-clear-title"
      onClose={onCancel}
      theme={theme}
    >
      <div className={styles.confirmHeading}>
        <TriangleAlert aria-hidden="true" />
        <h2 id="search-history-clear-title">검색 기록을 모두 지울까요?</h2>
      </div>
      <p className={styles.confirmDescription} id="search-history-clear-description">
        기록 {totalElements}건을 한 번에 지웁니다. 지운 기록은 되돌릴 수 없어요. 검색 결과나 이미
        접수한 문의는 그대로 남습니다.
      </p>
      {error ? <ApiErrorNotice error={error} /> : null}
      <div className={styles.confirmActions}>
        {/* 되돌릴 수 없는 동작이라 기본 포커스는 취소에 둔다. */}
        <button
          autoFocus
          className={styles.historyButton}
          disabled={isClearing}
          onClick={onCancel}
          type="button"
        >
          취소
        </button>
        <button
          className={styles.confirmDelete}
          disabled={isClearing}
          onClick={onConfirm}
          type="button"
        >
          {isClearing ? '지우는 중…' : '전체 삭제'}
        </button>
      </div>
    </SceneDialog>
  );
}
