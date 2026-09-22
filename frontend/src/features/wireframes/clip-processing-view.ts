import type {
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
      statuses.length === 0
        ? total
        : statuses.reduce((sum, status) => sum + runCounts[status], 0),
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
