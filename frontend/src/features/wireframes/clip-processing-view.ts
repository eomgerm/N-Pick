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
  return stageLabels[name] ?? name;
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
export function clipDetailPollInterval(status: ClipRunStatus | undefined, hasError = false) {
  return !hasError && isProcessingRun(status) ? 5_000 : false;
}
