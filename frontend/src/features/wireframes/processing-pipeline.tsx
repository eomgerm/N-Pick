'use client';

import { Check } from 'lucide-react';
import { type KeyboardEvent, useRef, useState } from 'react';

import type { ProcessingStage } from '@/features/wireframes/clip-processing-api';
import {
  processingStageLabel,
  processingStageOrder,
  processingTranscriptLabel,
  stageStatusLabels,
} from '@/features/wireframes/clip-processing-view';
import { formatInquiryDate } from '@/features/wireframes/review-inquiry-view';
import styles from '@/features/wireframes/reviewer-progress.module.css';

interface ProcessingPipelineProps {
  stages: ProcessingStage[];
}

function dateLabel(value: string | null) {
  return value === null ? '기록 없음' : formatInquiryDate(value);
}

export function ProcessingPipeline({ stages }: ProcessingPipelineProps) {
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const initialStage =
    processingStageOrder.find((name) =>
      stages.some((stage) => stage.name === name && stage.status === 'failed'),
    ) ??
    processingStageOrder.find((name) =>
      stages.some((stage) => stage.name === name && stage.status === 'running'),
    );
  const activeName = selectedName ?? initialStage ?? processingStageOrder[0];
  const activeStage = stages.find((stage) => stage.name === activeName);

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
        단계에 마우스를 올리거나 선택하면 상세 기록을 볼 수 있습니다.
      </p>
      <div className={styles.pipelineViewport}>
        <div className={styles.pipeline} role="tablist" aria-label="영상 처리 파이프라인 10단계">
          {processingStageOrder.map((name, index) => {
            const stage = stages.find((item) => item.name === name);
            const statusLabel = stage ? stageStatusLabels[stage.status] : '기록 없음';
            return (
              <div
                className={styles.pipelineStep}
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
                    if (event.pointerType === 'mouse') setSelectedName(name);
                  }}
                  onFocus={() => setSelectedName(name)}
                  onClick={() => setSelectedName(name)}
                  onKeyDown={(event) => handleKeyDown(event, index)}
                >
                  <span className={styles.stageMarker} aria-hidden="true">
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
      <div
        id="pipeline-stage-detail"
        className={styles.pipelineDetail}
        role="tabpanel"
        aria-labelledby={`pipeline-${activeName}`}
        tabIndex={0}
      >
        <div className={styles.detailHeading}>
          <h3>{processingStageLabel(activeName)}</h3>
          <span className={styles.chip} data-status={activeStage?.status}>
            {activeStage ? stageStatusLabels[activeStage.status] : '기록 없음'}
          </span>
        </div>
        {activeStage ? (
          <>
            {activeStage.error_code && (
              <p className={styles.stageError}>오류: {activeStage.error_code}</p>
            )}
            {activeStage.reason_code && (
              <p className={styles.stageMeta}>
                사유: {processingTranscriptLabel(activeStage.reason_code)}
              </p>
            )}
            <dl className={styles.pipelineFacts}>
              <div>
                <dt>시도 횟수</dt>
                <dd>{activeStage.attempts === null ? '미확인' : `${activeStage.attempts}회`}</dd>
              </div>
              <div>
                <dt>최대 시도</dt>
                <dd>
                  {activeStage.max_attempts === null ? '미확인' : `${activeStage.max_attempts}회`}
                </dd>
              </div>
              <div>
                <dt>시작</dt>
                <dd>{dateLabel(activeStage.started_at)}</dd>
              </div>
              <div>
                <dt>종료</dt>
                <dd>{dateLabel(activeStage.finished_at)}</dd>
              </div>
            </dl>
            <p className={styles.stageMeta}>
              자동 재시도:{' '}
              {activeStage.automatic_retryable === null
                ? '기록 미확인'
                : activeStage.automatic_retryable
                  ? '다음 시도 대기 중'
                  : '예약된 시도 없음'}
            </p>
            {activeStage.failed_attempts && activeStage.failed_attempts.length > 0 && (
              <ul className={styles.attemptList} aria-label="실패한 시도 기록">
                {activeStage.failed_attempts.map((attempt, index) => (
                  <li key={index}>
                    {attempt.attempt === null ? '회차 미확인' : `${attempt.attempt}회차`} ·{' '}
                    {attempt.error_code ?? '오류 코드 미확인'} · {dateLabel(attempt.finished_at)}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <p className={styles.stageMeta}>
            저장된 단계 기록이 없습니다. 처리 상태를 확인할 수 없습니다.
          </p>
        )}
      </div>
    </>
  );
}
