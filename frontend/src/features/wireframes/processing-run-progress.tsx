'use client';

import type { ClipRunStatus, ProcessingStage } from '@/features/wireframes/clip-processing-api';
import { processingRunProgress } from '@/features/wireframes/clip-processing-view';
import styles from '@/features/wireframes/processing-run-progress.module.css';

interface ProcessingRunProgressProps {
  runStatus: ClipRunStatus | null | undefined;
  stages: ProcessingStage[];
}

// 처리 상세의 10단계 진행 요약 막대 (S15P21A501-325). 상세 조회가 5초마다 새로 받는 stages 로만 그린다.
export function ProcessingRunProgress({ runStatus, stages }: ProcessingRunProgressProps) {
  const progress = processingRunProgress(runStatus, stages);
  if (!progress) return null;

  return (
    <div className={styles.summary} data-state={progress.state}>
      <div
        aria-label="영상 처리 진행률"
        aria-valuemax={progress.total}
        aria-valuemin={0}
        aria-valuenow={progress.done}
        aria-valuetext={progress.label}
        className={styles.track}
        role="progressbar"
      >
        <span style={{ width: `${(progress.done / progress.total) * 100}%` }} />
      </div>
      <p className={styles.label} role="status">
        {progress.label}
      </p>
    </div>
  );
}
