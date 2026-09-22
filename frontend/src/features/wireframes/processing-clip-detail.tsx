'use client';

import { useQuery } from '@tanstack/react-query';
import { AlertCircle, Check, ChevronDown, Film, RefreshCw } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClip } from '@/features/wireframes/clip-processing-api';
import {
  clipDetailPollInterval,
  clipRunLabels,
  processingAsrStatusLabel,
  processingProgressLabel,
  processingRecordLabel,
  processingStageLabel,
  processingTranscriptLabel as transcriptLabel,
} from '@/features/wireframes/clip-processing-view';
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
  const run = clip?.latest_run;
  const processing = data?.processing_details;
  const transcript = processing?.transcript;
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
                  <h1 ref={headingRef} tabIndex={-1}>
                    {displayClipTitle(clip.title)}
                  </h1>
                  <p className={styles.registrationMeta}>
                    {clip.source_type === 'broadcast' ? '방송 영상' : '보관 영상'}
                    <span>등록 {formatInquiryDate(clip.created_at)}</span>
                  </p>
                </div>
                <button
                  type="button"
                  className={styles.detailButton}
                  disabled={detail.isFetching}
                  onClick={() => detail.refetch()}
                >
                  <RefreshCw aria-hidden="true" />
                  상태 새로고침
                </button>
              </div>
              <ProcessingRefreshStatus state={refreshState} dataUpdatedAt={detail.dataUpdatedAt} />
              <div className={styles.runSummary} data-status={run?.status}>
                <div className={styles.runState} role="status">
                  <span className={styles.stateMark} aria-hidden="true">
                    {run?.status === 'succeeded' ? (
                      <Check />
                    ) : run?.status === 'failed' ? (
                      <AlertCircle />
                    ) : (
                      <Film />
                    )}
                  </span>
                  <div>
                    <strong>{clipRunLabels[run?.status ?? 'no_run']}</strong>
                    <p>
                      {run?.error_code
                        ? '영상 처리를 완료하지 못했습니다. 아래 처리 내역을 확인해 주세요.'
                        : clip.progress?.current_stage
                          ? processingStageLabel(clip.progress.current_stage)
                          : run?.status === 'queued'
                            ? '등록된 영상의 분석 시작을 기다리고 있습니다.'
                            : processingProgressLabel(clip.progress)}
                    </p>
                  </div>
                </div>
                <span className={styles.availability} data-available={clip.search_available}>
                  {clip.search_available ? '검색 가능' : '검색 미제공'}
                </span>
              </div>
              {!run && (
                <p className={styles.contextNotice}>
                  {refreshState.isAutomatic
                    ? '처리 기록을 확인하고 있습니다. 기록이 준비되면 자동으로 표시합니다.'
                    : canPoll && refreshState.mode === 'paused'
                      ? '아직 처리 기록이 없습니다. 자동 확인이 재개되면 다시 확인합니다.'
                      : '아직 처리 기록이 없습니다. ‘상태 새로고침’으로 다시 확인해 주세요.'}
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
              {clip.search_available &&
                run &&
                clip.active_pipeline_run_id !== run.pipeline_run_id && (
                  <p className={styles.contextNotice}>
                    이전 처리 결과로 검색을 제공하고 있습니다. 아래 기록은 최신 처리 시도입니다.
                  </p>
                )}
            </section>
            <section className={`${styles.panel} ${styles.detailSection}`} aria-label="원본 영상">
              <h2>원본 영상</h2>
              <video
                key={clipId}
                aria-label={displayClipTitle(clip.title) + ' 원본 영상'}
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
            <ProcessingPipeline stages={processing?.stages ?? []} />
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
          <section
            className={`${styles.panel} ${styles.detailSection} ${styles.transcriptPanel}`}
            aria-label="대사 처리 기록"
          >
            <h2>대사 처리 기록</h2>
            <h3>현재 검색 제공 결과</h3>
            <dl className={styles.facts}>
              <div>
                <dt>기본 대사 출처</dt>
                <dd>{transcriptLabel(data.default_transcript_source)}</dd>
              </div>
              <div>
                <dt>첨부 자막</dt>
                <dd>{data.has_subtitle ? '있음' : '없음'}</dd>
              </div>
              <div>
                <dt>첨부 대본</dt>
                <dd>{data.has_script ? '있음' : '없음'}</dd>
              </div>
            </dl>
            <details className={styles.transcriptDetails}>
              <summary>
                최신 처리 시도의 대사 선택 <ChevronDown aria-hidden="true" />
              </summary>
              <dl className={styles.facts}>
                <div>
                  <dt>음성 인식 상태</dt>
                  <dd>{processingAsrStatusLabel(data)}</dd>
                </div>
              </dl>
              {transcript ? (
                <>
                  {transcript.record_status !== 'legacy' && (
                    <p>{processingRecordLabel(transcript.record_status)}</p>
                  )}
                  <dl className={styles.facts}>
                    <div>
                      <dt>선택 단계</dt>
                      <dd>
                        {transcript.selection_stage
                          ? processingStageLabel(transcript.selection_stage)
                          : '미확인'}
                      </dd>
                    </div>
                    <div>
                      <dt>채택 출처</dt>
                      <dd>
                        {transcript.used_sources === null
                          ? '미확인'
                          : transcript.used_sources.length
                            ? transcript.used_sources.map(transcriptLabel).join(', ')
                            : '없음'}
                      </dd>
                    </div>
                    <div>
                      <dt>대표 출처</dt>
                      <dd>{transcriptLabel(transcript.representative_source)}</dd>
                    </div>
                    <div>
                      <dt>채택 사유</dt>
                      <dd>
                        {transcript.adoption_reasons === null
                          ? '미확인'
                          : transcript.adoption_reasons.length
                            ? transcript.adoption_reasons.map(transcriptLabel).join(', ')
                            : '기록 없음'}
                      </dd>
                    </div>
                    <div>
                      <dt>음성 인식 필요</dt>
                      <dd>
                        {transcript.asr_required === null
                          ? '미확인'
                          : transcript.asr_required
                            ? '필요'
                            : '불필요'}
                      </dd>
                    </div>
                    <div>
                      <dt>선택 사유</dt>
                      <dd>{transcriptLabel(transcript.selection_reason)}</dd>
                    </div>
                    <div>
                      <dt>음성 인식 사유</dt>
                      <dd>{transcriptLabel(transcript.asr_reason)}</dd>
                    </div>
                    <div>
                      <dt>음성 인식 후보 구간</dt>
                      <dd>
                        {transcript.asr_segment_count === null
                          ? '미확인'
                          : `${transcript.asr_segment_count}개`}
                      </dd>
                    </div>
                    <div>
                      <dt>내장 자막</dt>
                      <dd>{transcriptLabel(transcript.embedded_status)}</dd>
                    </div>
                  </dl>
                </>
              ) : (
                <p className={styles.emptyRecord}>저장된 대사 선택 기록이 없습니다.</p>
              )}
            </details>
          </section>
        </>
      )}
    </div>
  );
}
