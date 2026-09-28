'use client';

import styles from '@/features/wireframes/review-interpretation-editor.module.css';

interface PreviousParseCandidatesNoticeProps {
  /** 서버에 남은 대기 해석 후보 수. */
  count: number;
  isError: boolean;
  isBusy: boolean;
  /** 담은 뒤(또는 새로고침 뒤) 고친 내용이 아직 담기지 않았는지. 서버 후보는 다음 담기 때 교체된다. */
  hasUnsavedEdits: boolean;
  onDiscard: () => void;
  onRetry: () => void;
}

// 새로고침 뒤에도 서버에 남은 해석 교정 후보를 알린다 (S15P21A501-317). 칩은 되살리지 않고, 다시 담으면
// 이전 교정이 폐기된다는 것과 바로 폐기할 방법만 보여 준다. 편집만으로는 폐기하지 않는다 — 담기를 눌러야 교체된다.
export function PreviousParseCandidatesNotice({
  count,
  isError,
  isBusy,
  hasUnsavedEdits,
  onDiscard,
  onRetry,
}: PreviousParseCandidatesNoticeProps) {
  const unsaved = hasUnsavedEdits ? (
    <p className={styles.hint}>
      편집한 내용은 아직 담지 않았어요. 교정 담기를 누르면 이전 교정을 폐기하고 새로 담습니다.
    </p>
  ) : null;
  if (isError) {
    return (
      <p className={styles.hint}>
        저장해 둔 해석 교정을 불러오지 못했습니다.{' '}
        <button className="font-bold underline" onClick={onRetry} type="button">
          다시 불러오기
        </button>
      </p>
    );
  }
  if (!count) return unsaved;
  return (
    <div className={styles.errorGroup} role="status">
      <p className={styles.hint}>
        이전에 담은 해석 교정 {count}건이 있어요. 편집해서 다시 담으면 이전 교정은 폐기됩니다.
      </p>
      <button
        className={styles.secondaryButton}
        disabled={isBusy}
        onClick={onDiscard}
        type="button"
      >
        이전 교정 폐기
      </button>
      {unsaved}
    </div>
  );
}
