'use client';

import { useQuery } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClip } from '@/features/wireframes/clip-processing-api';
import {
  clipDetailPollInterval,
  clipRunLabels,
  defaultProcessingStage,
  processingProgressLabel,
  processingRecordLabel,
  processingStageLabel,
} from '@/features/wireframes/clip-processing-view';
import { ProcessingStageResults } from '@/features/wireframes/processing-analysis-results';
import { ProcessingFinalAnalysisResults } from '@/features/wireframes/processing-final-analysis-results';
import { ProcessingPipeline } from '@/features/wireframes/processing-pipeline';
import {
  ProcessingRefreshStatus,
  useProcessingRefreshState,
} from '@/features/wireframes/processing-refresh-status';
import { displayClipTitle, formatInquiryDate } from '@/features/wireframes/review-inquiry-view';
import styles from '@/features/wireframes/reviewer-progress.module.css';
import { createApiUrl } from '@/lib/api/client';

interface ProcessingClipDetailProps {
  clipId: string;
}

function dateLabel(value: string | null) {
  return value === null ? '기록 없음' : formatInquiryDate(value);
}

export function ProcessingClipDetail({ clipId }: ProcessingClipDetailProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const mediaRef = useRef<HTMLVideoElement>(null);
  const [hasMediaError, setHasMediaError] = useState(false);
  const detail = useQuery({
    queryKey: ['processing-clip', clipId],
    queryFn: ({ signal }) => getProcessingClip(clipId, signal),
    refetchInterval: (query) =>
      clipDetailPollInterval(
        query.state.data?.clip.latest_run === null
          ? null
          : query.state.data?.clip.latest_run?.status,
        query.state.status === 'error',
        query.state.data?.clip.created_at,
      ),
  });
  const loadedId = detail.data?.clip.clip_id;
  useEffect(() => {
    if (loadedId) headingRef.current?.focus({ preventScroll: true });
  }, [loadedId]);
  const data = detail.data;
  const clip = data?.clip;
  const title = displayClipTitle(clip?.title ?? null);
  const run = clip?.latest_run;
  const processing = data?.processing_details;
  const [stageSelection, setStageSelection] = useState<{ runId: string; name: string } | null>(
    null,
  );
  const runId = run?.pipeline_run_id ?? null;
  const activeStageName =
    stageSelection?.runId === runId
      ? stageSelection.name
      : defaultProcessingStage(processing?.stages ?? []);
  const activeStage = processing?.stages.find((stage) => stage.name === activeStageName);
  const registrationWindow = !run && clip ? `${clipId}:${clip.created_at}` : null;
  const registrationDeadline = !run && clip ? Date.parse(clip.created_at) + 60_000 : null;
  const [expiredRegistrationWindow, setExpiredRegistrationWindow] = useState<string | null>(null);
  useEffect(() => {
    if (registrationWindow === null || registrationDeadline === null) return;
    const timer = window.setTimeout(
      () => setExpiredRegistrationWindow(registrationWindow),
      Math.max(0, registrationDeadline - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [registrationWindow, registrationDeadline]);
  const canPoll = Boolean(
    clipDetailPollInterval(
      run === null ? null : run?.status,
      detail.isError,
      clip?.created_at,
      registrationWindow !== null && expiredRegistrationWindow === registrationWindow
        ? registrationDeadline!
        : detail.dataUpdatedAt,
    ),
  );
  const refreshState = useProcessingRefreshState({
    canPoll,
    hasError: detail.isError,
    fetchStatus: detail.fetchStatus,
  });

  function seekToScene(startTimeMs: number) {
    const media = mediaRef.current;
    if (!media) return;
    media.currentTime = startTimeMs / 1000;
    media.scrollIntoView({ behavior: 'auto', block: 'center' });
    void media.play().catch(() => undefined);
  }

  return (
    <div className={`${styles.page} ${styles.detailPage}`}>
      {detail.isPending ? (
        <p className="py-16 text-center text-white" role="status">
          영상 처리 기록을 불러오는 중…
        </p>
      ) : null}
      {detail.isError && (
        <section
          className={`${styles.panel} ${styles.detailSection}`}
          aria-label="처리 상세 조회 오류"
        >
          <ApiErrorNotice error={detail.error} />
          {data && <p>아래는 마지막으로 확인한 기록입니다. 최신 상태를 다시 확인해 주세요.</p>}
          <div className="mt-4 flex justify-end">
            <button
              className={styles.detailButton}
              type="button"
              disabled={detail.isFetching}
              onClick={() => detail.refetch()}
            >
              처리 상세 다시 시도
            </button>
          </div>
        </section>
      )}
      {clip && data && (
        <>
          <div className={styles.detailColumns}>
            <section
              className={`${styles.panel} ${styles.detailSection} ${styles.overview}`}
              aria-label="영상 처리 상세"
            >
              <div className={styles.detailHeading}>
                <div className={styles.titleCopy}>
                  <h1 ref={headingRef} tabIndex={-1} aria-label={title}>
                    {title}
                  </h1>
                  <p className={styles.registrationMeta}>
                    {clip.source_type === 'broadcast' ? '방송 영상' : '자료 영상'}
                    <span>등록 {formatInquiryDate(clip.created_at)}</span>
                    {clip.registered_by && <span>등록자 {clip.registered_by.login_id}</span>}
                  </p>
                </div>
                <div className={styles.detailActions}>
                  <button
                    type="button"
                    className={styles.detailButton}
                    disabled={detail.isFetching}
                    onClick={() => detail.refetch()}
                  >
                    <RefreshCw aria-hidden="true" />
                    상태 새로고침
                  </button>
                  <div className={styles.runSummary} data-status={run?.status}>
                    <strong className={styles.runState} role="status">
                      {clipRunLabels[run?.status ?? 'no_run']}
                    </strong>
                    <span className={styles.availability} data-available={clip.search_available}>
                      {clip.search_available ? '검색 가능' : '검색 미제공'}
                    </span>
                  </div>
                </div>
              </div>
              <ProcessingRefreshStatus state={refreshState} dataUpdatedAt={detail.dataUpdatedAt} />
              {!run && (
                <p className={styles.contextNotice}>
                  {refreshState.isAutomatic
                    ? '처리 기록을 확인하고 있습니다. 기록이 준비되면 자동으로 표시합니다.'
                    : canPoll && refreshState.mode === 'paused'
                      ? '아직 처리 기록이 없습니다. 자동 확인이 재개되면 다시 확인합니다.'
                      : '아직 처리 기록이 없습니다. ‘상태 새로고침’으로 다시 확인해 주세요.'}
                </p>
              )}
              {clip.search_available &&
                run &&
                clip.active_pipeline_run_id !== run.pipeline_run_id && (
                  <p className={styles.contextNotice}>
                    이전 처리 결과로 검색을 제공하고 있습니다. 아래 기록은 최신 처리 시도입니다.
                  </p>
                )}
              <dl className={`${styles.facts} ${styles.runFacts}`}>
                <div>
                  <dt>최신 처리</dt>
                  <dd>{run ? `${run.processing_no}차 처리` : '처리 기록 없음'}</dd>
                </div>
                <div>
                  <dt>처리 시작</dt>
                  <dd>{dateLabel(run?.started_at ?? null)}</dd>
                </div>
                <div>
                  <dt>처리 종료</dt>
                  <dd>{dateLabel(run?.finished_at ?? null)}</dd>
                </div>
              </dl>
            </section>
            <section
              className={`${styles.panel} ${styles.detailSection} ${styles.mediaSection}`}
              aria-label="원본 영상"
            >
              <h2>원본 영상</h2>
              <video
                ref={mediaRef}
                key={clipId}
                aria-label={title + ' 원본 영상'}
                className={styles.media}
                controls
                playsInline
                preload="metadata"
                crossOrigin="use-credentials"
                src={createApiUrl(`/media/${clipId}`).toString()}
                onError={() => setHasMediaError(true)}
                onLoadedMetadata={() => setHasMediaError(false)}
              />
              {hasMediaError && (
                <p className={styles.stageError} role="alert">
                  원본 영상을 재생할 수 없습니다. 파일 접근 권한과 영상 형식을 확인해 주세요.
                </p>
              )}
            </section>
          </div>
          <section
            className={`${styles.panel} ${styles.detailSection} ${styles.stagesPanel}`}
            aria-label="최신 처리 단계"
          >
            <div className={styles.sectionIntro}>
              <h2>최신 처리 단계</h2>
              <p>{processingProgressLabel(clip.progress)}</p>
            </div>
            {processing &&
              processing.record_status !== 'legacy' &&
              processingRecordLabel(processing.record_status) !==
                processingProgressLabel(clip.progress) && (
                <p className={styles.contextNotice}>
                  {processingRecordLabel(processing.record_status)}
                </p>
              )}
            <ProcessingPipeline
              activeName={activeStageName}
              onActiveNameChange={(name) => {
                if (runId) setStageSelection({ runId, name });
              }}
              stages={processing?.stages ?? []}
            />
            <ProcessingStageResults
              key={`${clipId}:${runId ?? 'no-run'}`}
              clipId={clipId}
              isProcessing={run?.status === 'queued' || run?.status === 'running'}
              onSeek={seekToScene}
              pipelineRunId={runId}
              stage={activeStage}
              stageName={activeStageName}
            />
            <dl className={styles.facts}>
              <div>
                <dt>실패·중단 단계</dt>
                <dd>
                  {processing
                    ? processing.failed_stages && processing.failed_stages.length
                      ? processing.failed_stages.map(processingStageLabel).join(', ')
                      : '확인된 실패 단계 없음'
                    : '미확인'}
                </dd>
              </div>
              <div>
                <dt>누락 채널</dt>
                <dd>
                  {processing?.missing_channels == null
                    ? '미확인'
                    : processing.missing_channels.length
                      ? processing.missing_channels.map(processingStageLabel).join(', ')
                      : '없음'}
                </dd>
              </div>
            </dl>
          </section>
          <ProcessingFinalAnalysisResults
            key={`${clipId}:${runId ?? 'no-run'}:final`}
            className={`${styles.panel} ${styles.detailSection}`}
            clipId={clipId}
            isProcessing={run?.status === 'queued' || run?.status === 'running'}
            onSeek={seekToScene}
            pipelineRunId={runId}
          />
        </>
      )}
    </div>
  );
}
