import { ApiClientError, fetchJson } from '@/lib/api/client';
import {
  inquiryResolutionLabels,
  type InquiryResolution,
  type InquiryStatus,
} from '@/features/wireframes/inquiry-state';

export interface MyInquiry {
  feedbackId: string;
  searchExecutionId: string;
  searchResultId: string;
  createdAt: string;
  updatedAt: string;
  queryText: string;
  comment: string | null;
  status: InquiryStatus;
  resolution: InquiryResolution | null;
  scene: {
    sceneId: string;
    clipId: string;
    clipTitle: string | null;
    startTimeMs: number;
    endTimeMs: number;
  };
}

export interface MyInquiryPage {
  items: MyInquiry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface ResultSnapshot {
  searchResultId: string;
  sceneId: string;
  rank: number;
  explain: Record<string, unknown>;
}

export interface MyInquiryDetail extends MyInquiry {
  explicitFilters: Record<string, unknown>;
  resolutionNote: string | null;
  reviewStartedAt: string | null;
  closedAt: string | null;
  snapshotStatus: 'available' | 'unavailable';
  resultSnapshot: ResultSnapshot | null;
}

export const myInquiryKeys = {
  all: ['my-inquiries'] as const,
  list: (memberId: string, page: number) => ['my-inquiries', memberId, 'list', page] as const,
  detail: (memberId: string, feedbackId: string) =>
    ['my-inquiries', memberId, 'detail', feedbackId] as const,
};

function fail(): never {
  throw new ApiClientError('invalid-response', 200);
}
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string') fail();
  return value;
}
function nullableText(value: unknown): string | null {
  return value === null ? null : text(value);
}
function id(value: unknown): string {
  const result = text(value);
  if (!/^[1-9]\d*$/.test(result)) fail();
  return result;
}
function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) fail();
  return value;
}
function timestamp(value: unknown): string {
  const result = text(value);
  if (!Number.isFinite(Date.parse(result))) fail();
  return result;
}

function parseItem(value: unknown): MyInquiry {
  const item = record(value);
  const scene = record(item.scene);
  const status =
    item.status === 'OPEN'
      ? 'open'
      : item.status === 'REVIEWING'
        ? 'reviewing'
        : item.status === 'CLOSED'
          ? 'closed'
          : fail();
  const resolution = nullableText(item.resolution);
  if (resolution !== null && !Object.hasOwn(inquiryResolutionLabels, resolution)) fail();
  const startTimeMs = integer(scene.start_time_ms);
  const endTimeMs = integer(scene.end_time_ms);
  if (endTimeMs <= startTimeMs) fail();
  return {
    feedbackId: id(item.feedback_id),
    searchExecutionId: id(item.search_execution_id),
    searchResultId: id(item.search_result_id),
    createdAt: timestamp(item.created_at),
    updatedAt: timestamp(item.updated_at),
    queryText: text(item.query_text),
    comment: nullableText(item.comment),
    status,
    resolution: resolution as InquiryResolution | null,
    scene: {
      sceneId: id(scene.scene_id),
      clipId: id(scene.clip_id),
      clipTitle: nullableText(scene.clip_title),
      startTimeMs,
      endTimeMs,
    },
  };
}

export function parseMyInquiryPage(value: unknown): MyInquiryPage {
  const data = record(value);
  if (!Array.isArray(data.items) || typeof data.has_next !== 'boolean') fail();
  const page = integer(data.page);
  const size = integer(data.size, 1);
  const totalElements = integer(data.total_elements);
  const totalPages = integer(data.total_pages);
  if (
    size > 100 ||
    data.items.length > size ||
    totalPages !== Math.ceil(totalElements / size) ||
    data.has_next !== page + 1 < totalPages
  )
    fail();
  return {
    items: data.items.map(parseItem),
    page,
    size,
    totalElements,
    totalPages,
    hasNext: data.has_next,
  };
}

function parseSnapshot(
  status: unknown,
  raw: unknown,
): { status: 'available' | 'unavailable'; resultSnapshot: ResultSnapshot | null } {
  // unavailable은 반드시 null을, available은 반드시 온전한 스냅샷을 동반한다. 그 외는 오류로 거른다 — 근거를 지어내지 않는다.
  if (status === 'unavailable') {
    if (raw !== null) fail();
    return { status: 'unavailable', resultSnapshot: null };
  }
  if (status === 'available') {
    const snap = record(raw);
    const explain = record(snap.explain);
    // BE 불변식과 대칭: 생산자(-59)는 display_name 에 nullable clip.title 을 그대로 기록한다. 문자열이거나
    // null(제목 없는 영상)이면 정상 스냅샷으로 보존하고, display 블록·키 부재나 비문자열은 계약 이탈로 거른다.
    // 대체 표기는 표현 계층이 정한다 — 여기서 근거를 지어내지 않는다.
    nullableText(record(explain.display).display_name);
    return {
      status: 'available',
      resultSnapshot: {
        searchResultId: id(snap.search_result_id),
        sceneId: id(snap.scene_id),
        rank: integer(snap.rank, 1),
        explain,
      },
    };
  }
  return fail();
}

export function parseMyInquiryDetail(value: unknown): MyInquiryDetail {
  const data = record(value);
  const snapshot = parseSnapshot(data.snapshot_status, data.result_snapshot);
  return {
    ...parseItem(data),
    explicitFilters: record(data.explicit_filters),
    resolutionNote: nullableText(data.resolution_note),
    reviewStartedAt: data.review_started_at === null ? null : timestamp(data.review_started_at),
    closedAt: data.closed_at === null ? null : timestamp(data.closed_at),
    snapshotStatus: snapshot.status,
    resultSnapshot: snapshot.resultSnapshot,
  };
}

export async function getMyInquiries(page: number, signal?: AbortSignal): Promise<MyInquiryPage> {
  const query = new URLSearchParams({ page: String(integer(page)), size: '10' });
  const result = parseMyInquiryPage(await fetchJson<unknown>('/inquiries', { query, signal }));
  if (result.page !== page) fail();
  return result;
}

export async function getMyInquiry(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<MyInquiryDetail> {
  const result = parseMyInquiryDetail(
    await fetchJson<unknown>(`/inquiries/${id(feedbackId)}`, { signal }),
  );
  if (result.feedbackId !== feedbackId) fail();
  return result;
}
