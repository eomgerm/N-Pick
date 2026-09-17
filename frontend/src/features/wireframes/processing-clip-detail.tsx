'use client';

import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { getProcessingClip } from '@/features/wireframes/clip-processing-api';
import {
  clipDetailPollInterval,
  clipRunLabels,
  processingProgressLabel,
  processingRecordLabel,
  processingStageLabel,
  stageStatusLabels,
} from '@/features/wireframes/clip-processing-view';
import { displayClipTitle, formatInquiryDate } from '@/features/wireframes/review-inquiry-view';
import styles from '@/features/wireframes/reviewer-progress.module.css';
import { createApiUrl } from '@/lib/api/client';

interface ProcessingClipDetailProps {
  clipId: string;
}

const transcriptLabels: Record<string, string> = {
  uploaded: '첨부 자막',
  embedded: '내장 자막',
  asr: '음성 인식',
  provided: '제공 자막',
  none: '없음',
  PREFERRED_SUBTITLE: '제공 자막 우선 사용',
  ASR_SUPPLEMENT: '음성 인식으로 보완',
  NO_SPEECH_DETECTED: '발화가 감지되지 않음',
  EXTRACTED: '추출됨',
  NO_TRACK: '자막 트랙 없음',
  UNSUPPORTED: '지원하지 않는 형식',
  NO_VALID_SEGMENTS: '유효한 자막 구간 없음',
  EXTRACTION_FAILED: '추출 실패',
  ...stageStatusLabels,
};
function transcriptLabel(value: string | null) {
  return value === null ? '미확인' : (transcriptLabels[value] ?? value);
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

  return (
    <div className={styles.page}>
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
          <button
            className={styles.detailButton}
            type="button"
            disabled={detail.isFetching}
            onClick={() => detail.refetch()}
          >
            처리 상세 다시 시도
          </button>
        </section>
      )}
      {clip && data && (
        <>
          <section
            className={`${styles.panel} ${styles.detailSection}`}
            aria-label="영상 처리 상세"
          >
            <div className={styles.detailHeading}>
              <h1 ref={headingRef} tabIndex={-1}>
                {displayClipTitle(clip.title)}
              </h1>
              <span className={styles.chip} data-status={run?.status}>
                {clipRunLabels[run?.status ?? 'no_run']}
              </span>
              <button
                type="button"
                className={styles.detailButton}
                disabled={detail.isFetching}
                onClick={() => detail.refetch()}
              >
                상태 새로고침
              </button>
            </div>
            {!run && (
              <p>아직 처리 기록이 없습니다. 잠시 후 ‘상태 새로고침’으로 다시 확인해 주세요.</p>
            )}
            <dl className={styles.facts}>
              <div>
                <dt>영상 유형</dt>
                <dd>{clip.source_type === 'broadcast' ? '방송 영상' : '보관 영상'}</dd>
              </div>
              <div>
                <dt>등록 시각</dt>
                <dd>{formatInquiryDate(clip.created_at)}</dd>
              </div>
              <div>
                <dt>검색 제공</dt>
                <dd>{clip.search_available ? '검색 가능' : '검색 미제공'}</dd>
              </div>
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
            {run?.error_code && <p role="status">처리 오류: {run.error_code}</p>}
            {clip.search_available &&
              run &&
              clip.active_pipeline_run_id !== run.pipeline_run_id && (
                <p>이전 처리 결과로 검색을 제공하고 있습니다. 아래 기록은 최신 처리 시도입니다.</p>
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
              <p role="alert">
                원본 영상을 재생할 수 없습니다. 파일 접근 권한과 영상 형식을 확인해 주세요.
              </p>
            )}
          </section>
          <section
            className={`${styles.panel} ${styles.detailSection}`}
            aria-label="최신 처리 단계"
          >
            <h2>최신 처리 단계</h2>
            <p>{processingProgressLabel(clip.progress)}</p>
            {processing && processing.record_status !== 'legacy' && (
              <p className={styles.videoDescription}>
                {processingRecordLabel(processing.record_status)}
              </p>
            )}
            {processing?.stages.length ? (
              <ol className={styles.stageList}>
                {processing.stages.map((stage) => (
                  <li key={stage.name}>
                    <div className={styles.detailHeading}>
                      <h3>{processingStageLabel(stage.name)}</h3>
                      <span className={styles.chip} data-status={stage.status}>
                        {stageStatusLabels[stage.status]}
                      </span>
                    </div>
                    <p>
                      시도 {stage.attempts === null ? '미확인' : `${stage.attempts}회`} · 최대{' '}
                      {stage.max_attempts === null ? '미확인' : `${stage.max_attempts}회`}
                    </p>
                    <p>
                      시작 {dateLabel(stage.started_at)} · 종료 {dateLabel(stage.finished_at)}
                    </p>
                    {stage.error_code && <p>오류: {stage.error_code}</p>}
                    {stage.reason_code && <p>사유: {transcriptLabel(stage.reason_code)}</p>}
                    <p>
                      자동 재시도:{' '}
                      {stage.automatic_retryable === null
                        ? '기록 미확인'
                        : stage.automatic_retryable
                          ? '다음 시도 대기 중'
                          : '예약된 시도 없음'}
                    </p>
                    {stage.failed_attempts && stage.failed_attempts.length > 0 ? (
                      <ul>
                        {stage.failed_attempts.map((attempt, index) => (
                          <li key={index}>
                            {attempt.attempt === null ? '회차 미확인' : `${attempt.attempt}회차`} ·{' '}
                            {attempt.error_code ?? '오류 코드 미확인'} ·{' '}
                            {dateLabel(attempt.finished_at)}
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </li>
                ))}
              </ol>
            ) : (
              <p>저장된 단계 기록이 없습니다.</p>
            )}
            <dl className={styles.facts}>
              <div>
                <dt>실패·중단 단계</dt>
                <dd>
                  {processing
                    ? processing.failed_stages.length
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
            className={`${styles.panel} ${styles.detailSection}`}
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
            <h3>최신 처리 시도의 대사 선택</h3>
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
                    <dt>음성 인식 상태</dt>
                    <dd>{transcriptLabel(transcript.asr_status)}</dd>
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
              <p>저장된 대사 선택 기록이 없습니다.</p>
            )}
          </section>
        </>
      )}
    </div>
  );
}
