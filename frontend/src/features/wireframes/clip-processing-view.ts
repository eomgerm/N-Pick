import type {
  ClipDetail,
  ClipProgress,
  ClipRunStatus,
  ProcessingRecordStatus,
  ProcessingStageStatus,
} from '@/features/wireframes/clip-processing-api';

export const clipRunLabels: Record<ClipRunStatus | 'no_run', string> = {
  queued: '처리 대기',
  running: '진행 중',
  failed: '확인 필요',
  succeeded: '처리 완료',
  no_run: '처리 기록 없음',
};
export const stageStatusLabels: Record<ProcessingStageStatus, string> = {
  pending: '대기',
  running: '진행 중',
  succeeded: '성공',
  failed: '실패',
  skipped: '생략',
  unknown: '미확인',
};
// docs/contracts/job-api.md §4.3.3의 파이프라인 실행 순서.
export const processingStageOrder = [
  'scene_detection',
  'frame_extraction',
  'ocr',
  'transcript_selection',
  'asr',
  'scene_transcript_mapping',
  'vlm_metadata',
  'entity_extraction',
  'text_embedding',
  'indexing',
] as const;
const stageLabels: Record<string, string> = {
  scene_detection: '장면 나누기',
  frame_extraction: '대표 화면 추출',
  transcript_selection: '대사 출처 선택',
  asr: '음성 인식',
  scene_transcript_mapping: '장면별 대사 연결',
  ocr: '영상 속 글자 읽기',
  vlm_metadata: '영상 설명 생성',
  entity_extraction: '개체 추출',
  text_embedding: '검색 임베딩 생성',
  indexing: '검색 반영',
};
export function processingStageLabel(name: string) {
  return stageLabels[name] ?? '기타 처리 단계';
}

const stageResultTitles: Record<string, string> = {
  scene_detection: '나눈 장면',
  frame_extraction: '추출한 대표 화면',
  ocr: '읽어낸 화면 글자',
  transcript_selection: '선택한 대사 출처',
  asr: '음성 인식 결과',
  scene_transcript_mapping: '연결한 장면 대사',
  vlm_metadata: '생성한 영상 설명',
  entity_extraction: '추출한 검색 태그',
  text_embedding: '검색 표현 생성 결과',
  indexing: '검색 반영 결과',
};

export function processingStageResultTitle(name: string) {
  return stageResultTitles[name] ?? '처리 결과';
}

const sceneResultStages = new Set([
  'scene_detection',
  'frame_extraction',
  'ocr',
  'transcript_selection',
  'scene_transcript_mapping',
  'vlm_metadata',
  'entity_extraction',
]);

export function processingStageResultMode(name: string): 'scene' | 'completion' {
  return sceneResultStages.has(name) ? 'scene' : 'completion';
}

export function defaultProcessingStage(
  stages: ReadonlyArray<{ name: string; status: ProcessingStageStatus }>,
) {
  return (
    processingStageOrder.find((name) =>
      stages.some((stage) => stage.name === name && stage.status === 'failed'),
    ) ??
    processingStageOrder.find((name) =>
      stages.some((stage) => stage.name === name && stage.status === 'running'),
    ) ??
    processingStageOrder[0]
  );
}
export interface ProcessingRunProgress {
  total: number;
  /** 성공·생략한 단계 수. 생략도 더 기다릴 일이 없으므로 완료로 센다. */
  done: number;
  state: 'waiting' | 'running' | 'failed' | 'done';
  /** 진행 중이거나 멈춘 단계. 단계 사이 대기 중이면 null. */
  currentName: string | null;
  label: string;
}

// 처리 상세의 10단계 진행 요약 (S15P21A501-325). 실패 → 진행 중 → 대기 순으로 우선한다.
export function processingRunProgress(
  runStatus: ClipRunStatus | null | undefined,
  stages: ReadonlyArray<{ name: string; status: ProcessingStageStatus }>,
): ProcessingRunProgress | null {
  // 단계 기록이 없으면 0단계 완료로 꾸미지 않는다 — 기록 없음은 목록·파이프라인이 따로 알린다.
  if (!runStatus || stages.length === 0) return null;
  const total = processingStageOrder.length;
  const statusOf = (name: string) => stages.find((stage) => stage.name === name)?.status;
  const done = processingStageOrder.filter((name) =>
    ['succeeded', 'skipped'].includes(statusOf(name) ?? ''),
  ).length;
  const counted = `${done}/${total} 완료`;
  const failed = processingStageOrder.find((name) => statusOf(name) === 'failed');
  if (failed) {
    return {
      total,
      done,
      state: 'failed',
      currentName: failed,
      label: `${counted} · ${processingStageLabel(failed)} 단계에서 멈춤`,
    };
  }
  const running = processingStageOrder.find((name) => statusOf(name) === 'running');
  if (running) {
    return {
      total,
      done,
      state: 'running',
      currentName: running,
      label: `${counted} · 지금 ${processingStageLabel(running)} 진행 중`,
    };
  }
  if (runStatus === 'succeeded') {
    return { total, done, state: 'done', currentName: null, label: counted };
  }
  if (runStatus === 'failed') {
    return { total, done, state: 'failed', currentName: null, label: `${counted} · 처리가 중단됨` };
  }
  return {
    total,
    done,
    state: 'waiting',
    currentName: null,
    label:
      runStatus === 'queued'
        ? `${counted} · 처리 서버가 작업을 가져가길 기다리는 중`
        : `${counted} · 다음 단계를 준비하는 중`,
  };
}
export function processingRecordLabel(status: ProcessingRecordStatus | undefined) {
  return status === 'available'
    ? '처리 기록 확인됨'
    : status === 'partial'
      ? '일부 처리 기록만 확인됨'
      : status === 'legacy'
        ? '이전 형식의 처리 기록'
        : status === 'unsupported_version'
          ? '지원하지 않는 버전의 처리 기록'
          : '처리 기록 미확인';
}
export function processingProgressLabel(progress: ClipProgress | null) {
  if (!progress || progress.total_steps === null)
    return processingRecordLabel(progress?.record_status);
  return `전체 ${progress.total_steps}단계 · 성공 ${progress.succeeded_steps} · 생략 ${progress.skipped_steps} · 실패 ${progress.failed_steps}`;
}
export function isProcessingRun(status: ClipRunStatus | undefined) {
  return status === 'queued' || status === 'running';
}
export function clipListPollInterval(
  counts: { queued: number; running: number } | undefined,
  hasError = false,
) {
  return !hasError && counts && counts.queued + counts.running > 0 ? 5_000 : false;
}
export function clipDetailPollInterval(
  status: ClipRunStatus | null | undefined,
  hasError = false,
  createdAt?: string,
  now = Date.now(),
) {
  if (hasError) return false;
  // null은 조회된 실행 없음, undefined는 아직 상세 응답이 없는 상태다.
  if (status === null) {
    const age = createdAt === undefined ? NaN : now - Date.parse(createdAt);
    return age >= 0 && age < 60_000 ? 5_000 : false;
  }
  return isProcessingRun(status) ? 5_000 : false;
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
export function processingTranscriptLabel(value: string | null) {
  return value === null ? '미확인' : (transcriptLabels[value] ?? '상세 사유를 확인할 수 없습니다.');
}

const asrStatusLabels: Record<string, string> = {
  pending: '대기',
  running: '처리 중',
  succeeded: '완료',
  failed: '실패',
  skipped: '생략',
};

export function processingAsrStatusLabel(detail: ClipDetail | undefined) {
  const processing = detail?.processing_details;
  if (!processing || processing.pipeline_run_id !== detail?.clip.latest_run?.pipeline_run_id)
    return '상태 정보 없음';
  const directStatus = processing.transcript?.asr_status;
  const stageStatus = processing.stages.find((stage) => stage.name === 'asr')?.status;
  // 대사 출처 기록의 누락은 별도로 저장된 ASR 단계 상태를 무효화하지 않는다.
  for (const status of [directStatus, stageStatus]) {
    if (status && Object.hasOwn(asrStatusLabels, status)) return asrStatusLabels[status];
  }
  return '상태 정보 없음';
}

export interface ProcessingRefreshStateInput {
  canPoll: boolean;
  hasError: boolean;
  fetchStatus: 'fetching' | 'paused' | 'idle';
  isOnline: boolean;
  isFocused: boolean;
}

export function processingRefreshState({
  canPoll,
  hasError,
  fetchStatus,
  isOnline,
  isFocused,
}: ProcessingRefreshStateInput) {
  const isAutomatic = canPoll && !hasError && isOnline && isFocused && fetchStatus !== 'paused';
  if (fetchStatus === 'paused' && !canPoll)
    return { mode: hasError ? 'error' : 'manual', isAutomatic: false } as const;
  const mode =
    fetchStatus === 'paused' || (canPoll && !hasError && (!isOnline || !isFocused))
      ? 'paused'
      : fetchStatus === 'fetching'
        ? 'refreshing'
        : hasError
          ? 'error'
          : isAutomatic
            ? 'automatic'
            : 'manual';
  return { mode, isAutomatic } as const;
}

/** 목록 칩. 서버가 아는 다섯 상태(`ClipQueryController` status 파라미터)를 네 묶음으로 접는다. */
export const CLIP_FILTERS = [
  { value: 'all', label: '전체', statuses: [] },
  { value: 'processing', label: '처리 중', statuses: ['queued', 'running'] },
  { value: 'attention', label: '확인 필요', statuses: ['failed', 'no_run'] },
  { value: 'done', label: '처리 완료', statuses: ['succeeded'] },
] as const;

export type ClipFilter = (typeof CLIP_FILTERS)[number]['value'];
type ClipRunCounts = Record<ClipRunStatus | 'no_run', number>;

export function clipFilterCounts(runCounts: ClipRunCounts): Record<ClipFilter, number> {
  const total = Object.values(runCounts).reduce((sum, count) => sum + count, 0);
  return Object.fromEntries(
    CLIP_FILTERS.map(({ value, statuses }) => [
      value,
      statuses.length === 0 ? total : statuses.reduce((sum, status) => sum + runCounts[status], 0),
    ]),
  ) as Record<ClipFilter, number>;
}

export function clipFilterStatuses(filter: ClipFilter): string[] {
  return [...(CLIP_FILTERS.find((item) => item.value === filter)?.statuses ?? [])];
}

export function selectClipFilter(raw: string | null): ClipFilter {
  return CLIP_FILTERS.some((item) => item.value === raw) ? (raw as ClipFilter) : 'all';
}

/**
 * 칩을 바꾸면 걸러진 목록의 페이지 수가 달라지므로 페이지를 처음으로 되돌린다.
 *
 * 키는 `clipStatus` 다. 문의 화면이 같은 URL 에서 `status` 로 open/reviewing/closed 를 쓰므로
 * 어휘가 겹치지 않도록 분리한다.
 */
export function clipFilterUpdates(filter: ClipFilter): Record<string, string | null> {
  return { clipStatus: filter === 'all' ? null : filter, progressPage: null };
}
