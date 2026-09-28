'use client';

import { TriangleAlert } from 'lucide-react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { SceneDialog } from '@/features/wireframes/scene-dialog';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

interface MySearchHistoryDeleteDialogProps {
  queryText: string;
  error: unknown;
  isDeleting: boolean;
  theme: WireframeTheme;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 검색 기록 삭제 확인 (S15P21A501-277).
 *
 * 휴지통이 없어 사용자가 되돌릴 수단이 없으므로 버튼 한 번으로 지우지 않는다. 서버가 행을 보존하는 것은 감사·신고를
 * 위한 것이지 복구 경로가 아니다.
 *
 * 기본 포커스를 취소에 두는 이유: 목록에서 Enter 로 삭제 버튼을 눌렀을 때 그 Enter 가 확인까지 밀고 들어가면 확인
 * 단계가 없는 것과 같다.
 */
export function MySearchHistoryDeleteDialog({
  queryText,
  error,
  isDeleting,
  theme,
  onCancel,
  onConfirm,
}: MySearchHistoryDeleteDialogProps) {
  return (
    <SceneDialog
      className={styles.confirmDialog}
      describedBy="search-history-delete-description"
      isLocked={isDeleting}
      labelledBy="search-history-delete-title"
      onClose={onCancel}
      theme={theme}
    >
      <div className={styles.confirmHeading}>
        <TriangleAlert aria-hidden="true" />
        <h2 id="search-history-delete-title">검색 기록을 지울까요?</h2>
      </div>
      <p className={styles.confirmQuery} title={queryText}>
        {queryText}
      </p>
      <p className={styles.confirmDescription} id="search-history-delete-description">
        지운 기록은 되돌릴 수 없어요. 검색 결과나 이미 접수한 문의는 그대로 남습니다.
      </p>
      {error ? <ApiErrorNotice error={error} /> : null}
      <div className={styles.confirmActions}>
        {/* 되돌릴 수 없는 동작이라 기본 포커스는 취소에 둔다. */}
        <button
          autoFocus
          className={styles.historyButton}
          disabled={isDeleting}
          onClick={onCancel}
          type="button"
        >
          취소
        </button>
        <button
          className={styles.confirmDelete}
          disabled={isDeleting}
          onClick={onConfirm}
          type="button"
        >
          {isDeleting ? '지우는 중…' : '삭제'}
        </button>
      </div>
    </SceneDialog>
  );
}
