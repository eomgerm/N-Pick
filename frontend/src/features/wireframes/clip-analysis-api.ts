import { ApiClientError, fetchJson } from '@/lib/api/client';

export type ClipAnalysisTranscriptSource = 'provided' | 'asr';
export type ClipAnalysisTagType =
  | 'person'
  | 'organization'
  | 'location'
  | 'facility'
  | 'keyword'
  | 'event'
  | 'season'
  | 'weather'
  | 'scene_type'
  | 'filmed_date'
  | 'broadcast_date';
export type ClipAnalysisTagScope = 'scene' | 'clip';
export type ClipAnalysisTagVerification = 'reviewer_verified' | 'verified' | 'unverified';

export interface ClipAnalysisTag {
  tag_id: string;
  type: ClipAnalysisTagType;
  name: string;
  match_value: string;
  scope: ClipAnalysisTagScope;
  source: string;
  verification: ClipAnalysisTagVerification;
}

export interface ClipAnalysisScene {
  scene_id: string;
  scene_index: number;
  start_time_ms: number;
  end_time_ms: number;
  representative_frame_timestamp_ms: number | null;
  caption: string | null;
  shot_type: string;
  transcript: { text: string; source: ClipAnalysisTranscriptSource | null } | null;
  tags: ClipAnalysisTag[];
  ocr_texts: string[];
}

export interface ClipAnalysisScenes {
  clip_id: string;
  pipeline_run_id: string;
  search_applied: boolean;
  summary: {
    total_scenes: number;
    captioned_scenes: number;
    transcript_scenes: number;
    tagged_scenes: number;
  };
  items: ClipAnalysisScene[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  has_next: boolean;
}

function fail(): never {
  throw new ApiClientError('invalid-response', 200);
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) fail();
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

function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null {
  return value === null ? null : parse(value);
}

function array<T>(value: unknown, parse: (value: unknown) => T): T[] {
  if (!Array.isArray(value)) fail();
  return value.map(parse);
}

function choice<T extends string>(value: unknown, options: readonly T[]): T {
  if (typeof value !== 'string' || !options.includes(value as T)) fail();
  return value as T;
}

const TAG_TYPES = [
  'person',
  'organization',
  'location',
  'facility',
  'keyword',
  'event',
  'season',
  'weather',
  'scene_type',
  'filmed_date',
  'broadcast_date',
] as const;

function parseTag(value: unknown): ClipAnalysisTag {
  const item = record(value);
  return {
    tag_id: identifier(item.tag_id),
    type: choice(item.type, TAG_TYPES),
    name: text(item.name),
    match_value: text(item.match_value),
    scope: choice(item.scope, ['scene', 'clip']),
    source: text(item.source),
    verification: choice(item.verification, ['reviewer_verified', 'verified', 'unverified']),
  };
}

function parseScene(value: unknown): ClipAnalysisScene {
  const item = record(value);
  const startTimeMs = integer(item.start_time_ms);
  const endTimeMs = integer(item.end_time_ms);
  if (endTimeMs <= startTimeMs) fail();
  const frameTimestamp = nullable(item.representative_frame_timestamp_ms, integer);
  if (frameTimestamp !== null && (frameTimestamp < startTimeMs || frameTimestamp >= endTimeMs)) {
    fail();
  }
  const transcript = nullable(item.transcript, (value) => {
    const data = record(value);
    return {
      text: text(data.text),
      source: nullable(data.source, (source) => choice(source, ['provided', 'asr'])),
    };
  });
  const tags = array(item.tags, parseTag);
  const ocrTexts = array(item.ocr_texts, text);
  if (new Set(tags.map((tag) => tag.tag_id)).size !== tags.length) fail();
  if (new Set(ocrTexts).size !== ocrTexts.length) fail();
  return {
    scene_id: identifier(item.scene_id),
    scene_index: integer(item.scene_index),
    start_time_ms: startTimeMs,
    end_time_ms: endTimeMs,
    representative_frame_timestamp_ms: frameTimestamp,
    caption: nullable(item.caption, text),
    shot_type: text(item.shot_type),
    transcript,
    tags,
    ocr_texts: ocrTexts,
  };
}

export function parseClipAnalysisScenes(value: unknown): ClipAnalysisScenes {
  const item = record(value);
  const summaryValue = record(item.summary);
  const summary = {
    total_scenes: integer(summaryValue.total_scenes),
    captioned_scenes: integer(summaryValue.captioned_scenes),
    transcript_scenes: integer(summaryValue.transcript_scenes),
    tagged_scenes: integer(summaryValue.tagged_scenes),
  };
  const result: ClipAnalysisScenes = {
    clip_id: identifier(item.clip_id),
    pipeline_run_id: identifier(item.pipeline_run_id),
    search_applied: boolean(item.search_applied),
    summary,
    items: array(item.items, parseScene),
    page: integer(item.page),
    size: integer(item.size),
    total_elements: integer(item.total_elements),
    total_pages: integer(item.total_pages),
    has_next: boolean(item.has_next),
  };
  if (result.size < 1 || result.size > 100 || result.items.length > result.size) fail();
  if (summary.total_scenes !== result.total_elements) fail();
  if (
    [summary.captioned_scenes, summary.transcript_scenes, summary.tagged_scenes].some(
      (count) => count > summary.total_scenes,
    )
  ) {
    fail();
  }
  const expectedPages =
    result.total_elements === 0 ? 0 : Math.ceil(result.total_elements / result.size);
  if (result.total_pages !== expectedPages) fail();
  if (result.has_next !== result.page + 1 < result.total_pages) fail();
  if (result.items.length > 0 && result.page >= result.total_pages) fail();
  if (new Set(result.items.map((scene) => scene.scene_id)).size !== result.items.length) fail();
  if (new Set(result.items.map((scene) => scene.scene_index)).size !== result.items.length) fail();
  if (
    result.items.some((scene) => scene.scene_index < 1 || scene.scene_index > result.total_elements)
  ) {
    fail();
  }
  return result;
}

export async function getClipAnalysisScenes(
  clipId: string,
  pipelineRunId: string,
  page: number,
  size: number,
  signal?: AbortSignal,
) {
  identifier(clipId);
  identifier(pipelineRunId);
  if (
    !Number.isSafeInteger(page) ||
    page < 0 ||
    !Number.isSafeInteger(size) ||
    size < 1 ||
    size > 100
  ) {
    fail();
  }
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  const result = parseClipAnalysisScenes(
    await fetchJson<unknown>(`/clips/${clipId}/runs/${pipelineRunId}/scenes`, { query, signal }),
  );
  if (result.clip_id !== clipId || result.pipeline_run_id !== pipelineRunId) fail();
  if (result.page !== page || result.size !== size) fail();
  return result;
}
