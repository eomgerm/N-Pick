import { ApiClientError, fetchJson } from '@/lib/api/client';

export type ClipRunStatus = 'queued' | 'running' | 'failed' | 'succeeded';
export type ProcessingRecordStatus =
  'available' | 'partial' | 'legacy' | 'unavailable' | 'unsupported_version';
export type ProcessingStageStatus =
  'pending' | 'running' | 'succeeded' | 'failed' | 'skipped' | 'unknown';

export interface ClipRun {
  pipeline_run_id: string;
  processing_no: number;
  status: ClipRunStatus;
  error_code: string | null;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
}

export interface ClipProgress {
  record_status: ProcessingRecordStatus;
  current_stage: string | null;
  total_steps: number | null;
  succeeded_steps: number | null;
  skipped_steps: number | null;
  failed_steps: number | null;
}

export interface ClipSummary {
  clip_id: string;
  title: string | null;
  source_type: 'broadcast' | 'archive';
  search_available: boolean;
  active_pipeline_run_id: string | null;
  created_at: string;
  updated_at: string;
  latest_run: ClipRun | null;
  progress: ClipProgress | null;
}

export interface ClipPage {
  items: ClipSummary[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  has_next: boolean;
  run_counts: Record<ClipRunStatus | 'no_run', number>;
}

export interface ProcessingStage {
  name: string;
  status: ProcessingStageStatus;
  attempts: number | null;
  started_at: string | null;
  finished_at: string | null;
  error_code: string | null;
  reason_code: string | null;
  automatic_retryable: boolean | null;
  max_attempts: number | null;
  failed_attempts: Array<{
    attempt: number | null;
    error_code: string | null;
    finished_at: string | null;
  }> | null;
}

export interface ProcessingTranscript {
  record_status: ProcessingRecordStatus;
  selection_stage: string | null;
  used_sources: string[] | null;
  adoption_reasons: string[] | null;
  representative_source: string | null;
  asr_required: boolean | null;
  selection_reason: string | null;
  asr_status: string | null;
  asr_reason: string | null;
  asr_segment_count: number | null;
  embedded_status: string | null;
}

export interface ClipDetail {
  clip: ClipSummary;
  default_transcript_source: 'provided' | 'asr' | 'none';
  has_subtitle: boolean;
  has_script: boolean;
  processing_details: {
    pipeline_run_id: string;
    record_status: ProcessingRecordStatus;
    stages: ProcessingStage[];
    failed_stages: string[] | null;
    missing_channels: string[] | null;
    retryable: boolean | null;
    transcript: ProcessingTranscript | null;
  } | null;
}

function fail(): never {
  throw new ApiClientError('invalid-response', 200);
}
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string') fail();
  return value;
}
function identifier(value: unknown): string {
  const id = text(value);
  if (!/^[1-9]\d*$/.test(id)) fail();
  return id;
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail();
  return value;
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') fail();
  return value;
}
function instant(value: unknown): string {
  const result = text(value);
  if (Number.isNaN(Date.parse(result))) fail();
  return result;
}
function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null {
  return value == null ? null : parse(value);
}
function array<T>(value: unknown, parse: (value: unknown) => T): T[] {
  if (!Array.isArray(value)) fail();
  return value.map(parse);
}
function choice<T extends string>(value: unknown, options: readonly T[]): T {
  if (typeof value !== 'string' || !options.includes(value as T)) fail();
  return value as T;
}
function recordStatus(value: unknown): ProcessingRecordStatus {
  return choice(value, ['available', 'partial', 'legacy', 'unavailable', 'unsupported_version']);
}
function parseRun(value: unknown): ClipRun {
  const item = record(value);
  const processingNo = integer(item.processing_no);
  if (processingNo < 1) fail();
  return {
    pipeline_run_id: identifier(item.pipeline_run_id),
    processing_no: processingNo,
    status: choice(item.status, ['queued', 'running', 'failed', 'succeeded']),
    error_code: nullable(item.error_code, text),
    created_at: instant(item.created_at),
    started_at: nullable(item.started_at, instant),
    finished_at: nullable(item.finished_at, instant),
  };
}
function parseProgress(value: unknown): ClipProgress {
  const item = record(value);
  const result = {
    record_status: recordStatus(item.record_status),
    current_stage: nullable(item.current_stage, text),
    total_steps: nullable(item.total_steps, integer),
    succeeded_steps: nullable(item.succeeded_steps, integer),
    skipped_steps: nullable(item.skipped_steps, integer),
    failed_steps: nullable(item.failed_steps, integer),
  };
  const counts = [
    result.total_steps,
    result.succeeded_steps,
    result.skipped_steps,
    result.failed_steps,
  ];
  if (result.record_status === 'available' || result.record_status === 'legacy') {
    if (counts.some((count) => count === null)) fail();
    if (
      result.succeeded_steps! + result.skipped_steps! + result.failed_steps! >
      result.total_steps!
    )
      fail();
  } else if (counts.some((count) => count !== null)) fail();
  return result;
}
export function parseClipSummary(value: unknown): ClipSummary {
  const item = record(value);
  const result: ClipSummary = {
    clip_id: identifier(item.clip_id),
    title: nullable(item.title, text),
    source_type: choice(item.source_type, ['broadcast', 'archive']),
    search_available: boolean(item.search_available),
    active_pipeline_run_id: nullable(item.active_pipeline_run_id, identifier),
    created_at: instant(item.created_at),
    updated_at: instant(item.updated_at),
    latest_run: nullable(item.latest_run, parseRun),
    progress: nullable(item.progress, parseProgress),
  };
  if (result.search_available !== (result.active_pipeline_run_id !== null)) fail();
  if (!result.latest_run && result.progress) fail();
  return result;
}
export function parseClipPage(value: unknown): ClipPage {
  const item = record(value);
  const counts = record(item.run_counts);
  const result: ClipPage = {
    items: array(item.items, parseClipSummary),
    page: integer(item.page),
    size: integer(item.size),
    total_elements: integer(item.total_elements),
    total_pages: integer(item.total_pages),
    has_next: boolean(item.has_next),
    run_counts: {
      queued: integer(counts.queued),
      running: integer(counts.running),
      failed: integer(counts.failed),
      succeeded: integer(counts.succeeded),
      no_run: integer(counts.no_run),
    },
  };
  if (result.size < 1 || result.size > 100 || result.items.length > result.size) fail();
  if (new Set(result.items.map((clip) => clip.clip_id)).size !== result.items.length) fail();
  return result;
}
function parseStage(value: unknown): ProcessingStage {
  const item = record(value);
  return {
    name: text(item.name),
    status: choice(item.status, [
      'pending',
      'running',
      'succeeded',
      'failed',
      'skipped',
      'unknown',
    ]),
    attempts: nullable(item.attempts, integer),
    started_at: nullable(item.started_at, instant),
    finished_at: nullable(item.finished_at, instant),
    error_code: nullable(item.error_code, text),
    reason_code: nullable(item.reason_code, text),
    automatic_retryable: nullable(item.automatic_retryable, boolean),
    max_attempts: nullable(item.max_attempts, integer),
    failed_attempts: nullable(item.failed_attempts, (values) =>
      array(values, (value) => {
        const attempt = record(value);
        return {
          attempt: nullable(attempt.attempt, integer),
          error_code: nullable(attempt.error_code, text),
          finished_at: nullable(attempt.finished_at, instant),
        };
      }),
    ),
  };
}
function parseTranscript(value: unknown): ProcessingTranscript {
  const item = record(value);
  return {
    record_status: recordStatus(item.record_status),
    selection_stage: nullable(item.selection_stage, text),
    used_sources: nullable(item.used_sources, (values) => array(values, text)),
    adoption_reasons: nullable(item.adoption_reasons, (values) => array(values, text)),
    representative_source: nullable(item.representative_source, text),
    asr_required: nullable(item.asr_required, boolean),
    selection_reason: nullable(item.selection_reason, text),
    asr_status: nullable(item.asr_status, text),
    asr_reason: nullable(item.asr_reason, text),
    asr_segment_count: nullable(item.asr_segment_count, integer),
    embedded_status: nullable(item.embedded_status, text),
  };
}
export function parseClipDetail(value: unknown): ClipDetail {
  const item = record(value);
  const clip = parseClipSummary(item.clip);
  const details = nullable(item.processing_details, (value) => {
    const detail = record(value);
    const stages = array(detail.stages, parseStage);
    if (new Set(stages.map((stage) => stage.name)).size !== stages.length) fail();
    return {
      pipeline_run_id: identifier(detail.pipeline_run_id),
      record_status: recordStatus(detail.record_status),
      stages,
      // record_status 가 unavailable 이면 백엔드가 null 로 보낸다(형제 필드와 같은 규약).
      failed_stages: nullable(detail.failed_stages, (values) => array(values, text)),
      missing_channels: nullable(detail.missing_channels, (values) => array(values, text)),
      retryable: nullable(detail.retryable, boolean),
      transcript: nullable(detail.transcript, parseTranscript),
    };
  });
  if (details && details.pipeline_run_id !== clip.latest_run?.pipeline_run_id) fail();
  return {
    clip,
    processing_details: details,
    default_transcript_source: choice(item.default_transcript_source, ['provided', 'asr', 'none']),
    has_subtitle: boolean(item.has_subtitle),
    has_script: boolean(item.has_script),
  };
}

export async function getProcessingClips(page: number, completed: boolean, signal?: AbortSignal) {
  const query = new URLSearchParams({
    page: String(page),
    size: '10',
    status: completed ? 'succeeded' : 'queued,running,failed,no_run',
  });
  return parseClipPage(await fetchJson<unknown>('/clips', { query, signal }));
}
export async function getProcessingClip(clipId: string, signal?: AbortSignal) {
  identifier(clipId);
  const detail = parseClipDetail(await fetchJson<unknown>(`/clips/${clipId}`, { signal }));
  if (detail.clip.clip_id !== clipId) fail();
  return detail;
}
