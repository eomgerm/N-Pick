'use client';

import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Play, RefreshCw } from 'lucide-react';
import { type KeyboardEvent, type TouchEvent, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getClipAnalysisScenes } from '@/features/wireframes/clip-analysis-api';
import {
  formatSceneInterval,
  sceneSwipeDirection,
  transcriptSourceLabel,
} from '@/features/wireframes/clip-analysis-view';
import {
  ProcessingSceneFrame,
  ProcessingSceneTags,
} from '@/features/wireframes/processing-analysis-results';

import styles from './processing-analysis-results.module.css';

const PAGE_SIZE = 20;

interface ProcessingFinalAnalysisResultsProps {
  className?: string;
  clipId: string;
  pipelineRunId: string | null;
  isProcessing: boolean;
  onSeek: (startTimeMs: number) => void;
}

export function ProcessingFinalAnalysisResults({
  className,
  clipId,
  pipelineRunId,
  isProcessing,
  onSeek,
}: ProcessingFinalAnalysisResultsProps) {
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
    <section className={`${className ?? ''} ${styles.finalRoot}`} aria-label="최종 분석 결과">
      <div className={styles.heading}>
        <div>
          <span className={styles.eyebrow}>등록된 검색 정보</span>
          <h2>최종 분석 결과</h2>
        </div>
        {data && !data.search_applied ? (
          <span className={styles.unpublished}>검색 미반영 결과</span>
        ) : null}
      </div>

      {pipelineRunId === null ? (
        <p className={styles.empty}>처리가 완료되면 최종 분석 결과가 여기에 표시됩니다.</p>
      ) : analysis.isPending || (!data && analysis.isFetching) ? (
        <p className={styles.empty} role="status">
          최종 분석 결과를 불러오는 중…
        </p>
      ) : analysis.isError ? (
        <div className={styles.error}>
          <ApiErrorNotice error={analysis.error} />
          <button disabled={analysis.isFetching} onClick={() => analysis.refetch()} type="button">
            <RefreshCw aria-hidden="true" />
            최종 분석 결과 다시 시도
          </button>
        </div>
      ) : scene && data ? (
        <>
          <dl className={styles.summary} aria-label="최종 분석 결과 요약">
            <div>
              <dt>전체 장면</dt>
              <dd>{data.summary.total_scenes}개</dd>
            </div>
            <div>
              <dt>설명 생성</dt>
              <dd>{data.summary.captioned_scenes}개</dd>
            </div>
            <div>
              <dt>대사 연결</dt>
              <dd>{data.summary.transcript_scenes}개</dd>
            </div>
            <div>
              <dt>태그 생성</dt>
              <dd>{data.summary.tagged_scenes}개</dd>
            </div>
          </dl>
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
                <h4>영상 설명</h4>
                <p>{scene.caption ?? '생성된 영상 설명이 없습니다.'}</p>
              </div>
              <div className={styles.resultBlock}>
                <h4>최종 대사</h4>
                {scene.transcript ? (
                  <span className={styles.source}>
                    {transcriptSourceLabel(scene.transcript.source)}
                  </span>
                ) : null}
                <p>{scene.transcript?.text ?? '연결된 대사가 없습니다.'}</p>
              </div>
              <div className={styles.resultBlock}>
                <h4>검색 태그</h4>
                <ProcessingSceneTags scene={scene} />
              </div>
              <div className={styles.resultBlock}>
                <h4>화면 속 글자</h4>
                <p className={styles.ocrText}>
                  {scene.ocr_texts.length
                    ? scene.ocr_texts.join(' · ')
                    : '화면에서 읽어낸 글자가 없습니다.'}
                </p>
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
    </section>
  );
}
