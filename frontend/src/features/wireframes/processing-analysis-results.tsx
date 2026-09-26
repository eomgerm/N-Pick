'use client';

import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, ImageOff, Play, RefreshCw } from 'lucide-react';
import { type KeyboardEvent, type TouchEvent, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  getClipAnalysisScenes,
  type ClipAnalysisScene,
} from '@/features/wireframes/clip-analysis-api';
import {
  formatSceneInterval,
  sceneSwipeDirection,
  tagScopeLabel,
  tagTypeLabel,
  tagVerificationLabel,
  transcriptSourceLabel,
} from '@/features/wireframes/clip-analysis-view';
import type { ProcessingStage } from '@/features/wireframes/clip-processing-api';
import {
  processingStageLabel,
  processingStageResultTitle,
  processingTranscriptLabel,
  stageStatusLabels,
} from '@/features/wireframes/clip-processing-view';
import { SceneThumbnail } from '@/features/wireframes/scene-thumbnail';
import { createApiUrl } from '@/lib/api/client';

import styles from './processing-analysis-results.module.css';

const PAGE_SIZE = 20;

interface ProcessingStageResultsProps {
  clipId: string;
  pipelineRunId: string | null;
  isProcessing: boolean;
  onSeek: (startTimeMs: number) => void;
  stage: ProcessingStage | undefined;
  stageName: string;
}

export function ProcessingSceneFrame({ scene }: { scene: ClipAnalysisScene }) {
  return (
    <div className={styles.frame}>
      {scene.representative_frame_timestamp_ms === null ? (
        <span className={styles.frameEmpty}>
          <ImageOff aria-hidden="true" />
          대표 프레임이 없습니다.
        </span>
      ) : (
        <SceneThumbnail
          alt={`장면 ${scene.scene_index} 대표 프레임`}
          src={createApiUrl(`/scenes/${scene.scene_id}/thumbnail`).toString()}
        />
      )}
    </div>
  );
}

export function ProcessingSceneTags({ scene }: { scene: ClipAnalysisScene }) {
  return scene.tags.length ? (
    <ul className={styles.tags} aria-label={`장면 ${scene.scene_index} 태그`}>
      {scene.tags.map((tag) => (
        <li key={tag.tag_id}>
          <span>{tagTypeLabel(tag.type)}</span>
          <strong>{tag.name}</strong>
          <small>
            {tagScopeLabel(tag.scope)} · {tagVerificationLabel(tag.verification)}
          </small>
        </li>
      ))}
    </ul>
  ) : (
    <p>추출된 검색 태그가 없습니다.</p>
  );
}

function StageOutput({
  scene,
  searchApplied,
  stageName,
}: {
  scene: ClipAnalysisScene;
  searchApplied: boolean;
  stageName: string;
}) {
  switch (stageName) {
    case 'scene_detection':
      return (
        <p>{formatSceneInterval(scene.start_time_ms, scene.end_time_ms)} 구간으로 나눴습니다.</p>
      );
    case 'frame_extraction':
      return (
        <p>
          {scene.representative_frame_timestamp_ms === null
            ? '이 장면에서 대표 화면을 추출하지 못했습니다.'
            : `${formatSceneInterval(scene.representative_frame_timestamp_ms, scene.end_time_ms).split('–')[0]} 지점의 화면을 대표로 선택했습니다.`}
        </p>
      );
    case 'ocr':
      return scene.ocr_texts.length ? (
        <p className={styles.ocrText}>{scene.ocr_texts.join(' · ')}</p>
      ) : (
        <p>화면에서 읽어낸 글자가 없습니다.</p>
      );
    case 'transcript_selection':
      return (
        <>
          <strong className={styles.outputValue}>
            {scene.transcript ? transcriptSourceLabel(scene.transcript.source) : '선택된 대사 없음'}
          </strong>
          <p>{scene.transcript?.text ?? '이 장면에 연결할 대사 출처가 없습니다.'}</p>
        </>
      );
    case 'asr':
      return scene.transcript?.source === 'asr' ? (
        <p>{scene.transcript.text}</p>
      ) : (
        <p>
          {scene.transcript
            ? '제공 자막이 채택되어 이 장면에는 음성 인식 대사를 사용하지 않았습니다.'
            : '이 장면에서 사용할 수 있는 음성 인식 대사가 없습니다.'}
        </p>
      );
    case 'scene_transcript_mapping':
      return (
        <>
          {scene.transcript ? (
            <span className={styles.source}>{transcriptSourceLabel(scene.transcript.source)}</span>
          ) : null}
          <p>{scene.transcript?.text ?? '이 장면에 연결된 대사가 없습니다.'}</p>
        </>
      );
    case 'vlm_metadata':
      return <p>{scene.caption ?? '생성된 영상 설명이 없습니다.'}</p>;
    case 'entity_extraction':
      return <ProcessingSceneTags scene={scene} />;
    case 'text_embedding':
      return (
        <p>
          {scene.embedding_ready
            ? '이 장면의 검색 표현이 생성되어 유사 장면 비교에 사용할 수 있습니다.'
            : '이 장면의 검색 표현이 생성되지 않았습니다.'}
        </p>
      );
    case 'indexing':
      return (
        <p>
          {searchApplied
            ? '이 처리 결과가 현재 장면 검색에 반영되어 있습니다.'
            : '처리는 완료됐지만 현재 장면 검색에는 아직 반영되지 않았습니다.'}
        </p>
      );
    default:
      return <p>표시할 처리 결과가 없습니다.</p>;
  }
}

function StageRecord({ stage }: { stage: ProcessingStage | undefined }) {
  if (!stage) return <p className={styles.empty}>저장된 단계 기록이 없습니다.</p>;
  return (
    <div className={styles.stageRecord}>
      <strong>{stageStatusLabels[stage.status]}</strong>
      {stage.error_code ? <p>이 단계의 처리를 완료하지 못했습니다.</p> : null}
      {stage.reason_code ? <p>사유: {processingTranscriptLabel(stage.reason_code)}</p> : null}
      {stage.attempts !== null ? <p>시도 횟수 {stage.attempts}회</p> : null}
      {stage.automatic_retryable ? <p>자동 재시도를 기다리고 있습니다.</p> : null}
    </div>
  );
}

export function ProcessingStageResults({
  clipId,
  pipelineRunId,
  isProcessing,
  onSeek,
  stage,
  stageName,
}: ProcessingStageResultsProps) {
  const [scenePosition, setScenePosition] = useState(0);
  const touchStartX = useRef<number | null>(null);
  const page = Math.floor(scenePosition / PAGE_SIZE);
  const analysis = useQuery({
    queryKey: ['clip-analysis-scenes', clipId, pipelineRunId, page, isProcessing],
    queryFn: ({ signal }) => getClipAnalysisScenes(clipId, pipelineRunId!, page, PAGE_SIZE, signal),
    enabled: pipelineRunId !== null,
    placeholderData: (previous) => previous,
    refetchInterval: isProcessing ? 5_000 : false,
  });
  const data = analysis.data?.page === page ? analysis.data : undefined;
  const scene = data?.items[scenePosition % PAGE_SIZE];
  const totalScenes = data?.total_elements ?? 0;
  const hasPrevious = scenePosition > 0;
  const hasNext = totalScenes > 0 && scenePosition + 1 < totalScenes;
  const isSucceeded = stage?.status === 'succeeded';

  function moveScene(direction: 'previous' | 'next') {
    if (direction === 'previous' && hasPrevious) setScenePosition((current) => current - 1);
    if (direction === 'next' && hasNext) setScenePosition((current) => current + 1);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    moveScene(event.key === 'ArrowLeft' ? 'previous' : 'next');
  }

  function handleTouchEnd(event: TouchEvent<HTMLElement>) {
    const startX = touchStartX.current;
    touchStartX.current = null;
    if (startX === null) return;
    const direction = sceneSwipeDirection(startX, event.changedTouches[0]?.clientX ?? startX);
    if (direction) moveScene(direction);
  }

  return (
    <section className={styles.root} aria-label="단계별 처리 결과">
      <div
        id="pipeline-stage-detail"
        role="tabpanel"
        aria-labelledby={`pipeline-${stageName}`}
        tabIndex={0}
      >
        <div className={styles.heading}>
          <div>
            <span className={styles.eyebrow}>{processingStageLabel(stageName)} 결과</span>
            <h2>{processingStageResultTitle(stageName)}</h2>
          </div>
          {data && !data.search_applied ? (
            <span className={styles.unpublished}>검색 미반영 결과</span>
          ) : null}
        </div>

        {!isSucceeded ? (
          <StageRecord stage={stage} />
        ) : pipelineRunId === null ? (
          <p className={styles.empty}>처리가 시작되면 단계별 결과가 여기에 표시됩니다.</p>
        ) : analysis.isPending || (!data && analysis.isFetching) ? (
          <p className={styles.empty} role="status">
            분석 결과를 불러오는 중…
          </p>
        ) : analysis.isError ? (
          <div className={styles.error}>
            <ApiErrorNotice error={analysis.error} />
            <button disabled={analysis.isFetching} onClick={() => analysis.refetch()} type="button">
              <RefreshCw aria-hidden="true" />
              분석 결과 다시 시도
            </button>
          </div>
        ) : scene && data ? (
          <>
            <div className={styles.sceneNavigation}>
              <p aria-live="polite">
                장면 {scene.scene_index} / {data.total_elements}
                <span>{formatSceneInterval(scene.start_time_ms, scene.end_time_ms)}</span>
              </p>
              <div>
                <button
                  aria-label="이전 장면"
                  disabled={!hasPrevious || analysis.isFetching}
                  onClick={() => moveScene('previous')}
                  type="button"
                >
                  <ChevronLeft aria-hidden="true" />
                </button>
                <button
                  aria-label="다음 장면"
                  disabled={!hasNext || analysis.isFetching}
                  onClick={() => moveScene('next')}
                  type="button"
                >
                  <ChevronRight aria-hidden="true" />
                </button>
              </div>
            </div>
            <article
              className={styles.scene}
              onKeyDown={handleKeyDown}
              onTouchStart={(event) => {
                touchStartX.current = event.touches[0]?.clientX ?? null;
              }}
              onTouchEnd={handleTouchEnd}
              tabIndex={0}
            >
              <ProcessingSceneFrame scene={scene} />
              <div className={styles.sceneBody}>
                <div className={styles.sceneHeading}>
                  <h3>장면 {String(scene.scene_index).padStart(2, '0')}</h3>
                  <button
                    aria-label={`장면 ${scene.scene_index} 영상에서 보기`}
                    className={styles.seekButton}
                    onClick={() => onSeek(scene.start_time_ms)}
                    type="button"
                  >
                    <Play aria-hidden="true" />
                    영상에서 보기
                  </button>
                </div>
                <div className={styles.resultBlock}>
                  <StageOutput
                    scene={scene}
                    searchApplied={data.search_applied}
                    stageName={stageName}
                  />
                </div>
              </div>
            </article>
            <p className={styles.swipeHint}>좌우로 넘겨 다른 장면을 확인할 수 있습니다.</p>
          </>
        ) : (
          <p className={styles.empty}>
            {isProcessing
              ? '장면 분석을 진행하고 있습니다. 생성된 결과가 아직 없습니다.'
              : '이 처리에서 생성된 장면이 없습니다.'}
          </p>
        )}
      </div>
    </section>
  );
}
