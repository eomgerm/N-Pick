'use client';

import { Check } from 'lucide-react';
import { type KeyboardEvent, useRef } from 'react';

import type { ProcessingStage } from '@/features/wireframes/clip-processing-api';
import {
  processingStageLabel,
  processingStageOrder,
  stageStatusLabels,
} from '@/features/wireframes/clip-processing-view';
import motion from '@/features/wireframes/processing-run-progress.module.css';
import styles from '@/features/wireframes/reviewer-progress.module.css';

interface ProcessingPipelineProps {
  activeName: string;
  onActiveNameChange: (name: string) => void;
  stages: ProcessingStage[];
}

export function ProcessingPipeline({
  activeName,
  onActiveNameChange,
  stages,
}: ProcessingPipelineProps) {
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let nextIndex: number;
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % processingStageOrder.length;
    else if (event.key === 'ArrowLeft')
      nextIndex = (index - 1 + processingStageOrder.length) % processingStageOrder.length;
    else if (event.key === 'Home') nextIndex = 0;
    else if (event.key === 'End') nextIndex = processingStageOrder.length - 1;
    else return;
    event.preventDefault();
    tabRefs.current[nextIndex]?.focus();
  }

  return (
    <>
      <p className={styles.pipelineHint}>
        단계를 선택하면 아래에서 실제 처리 결과를 확인할 수 있습니다.
      </p>
      <div className={styles.pipelineViewport}>
        <div className={styles.pipeline} role="tablist" aria-label="영상 처리 파이프라인 10단계">
          {processingStageOrder.map((name, index) => {
            const stage = stages.find((item) => item.name === name);
            const statusLabel = stage ? stageStatusLabels[stage.status] : '기록 없음';
            return (
              <div
                className={`${styles.pipelineStep} ${motion.step}`}
                key={name}
                role="presentation"
                data-status={stage?.status}
              >
                <button
                  ref={(node) => {
                    tabRefs.current[index] = node;
                  }}
                  id={`pipeline-${name}`}
                  type="button"
                  role="tab"
                  aria-selected={activeName === name}
                  aria-controls="pipeline-stage-detail"
                  aria-label={`${index + 1}단계 ${processingStageLabel(name)} · ${statusLabel}`}
                  tabIndex={activeName === name ? 0 : -1}
                  onPointerEnter={(event) => {
                    if (event.pointerType === 'mouse') onActiveNameChange(name);
                  }}
                  onFocus={() => onActiveNameChange(name)}
                  onClick={() => onActiveNameChange(name)}
                  onKeyDown={(event) => handleKeyDown(event, index)}
                >
                  <span className={`${styles.stageMarker} ${motion.marker}`} aria-hidden="true">
                    {stage?.status === 'succeeded' ? <Check /> : String(index + 1).padStart(2, '0')}
                  </span>
                  <strong>{processingStageLabel(name)}</strong>
                  <span className={styles.pipelineStatus}>{statusLabel}</span>
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}
