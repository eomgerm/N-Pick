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

export interface MyInquiryDetail extends MyInquiry {
  explicitFilters: Record<string, unknown>;
  resolutionNote: string | null;
  reviewStartedAt: string | null;
  closedAt: string | null;
  snapshotStatus: 'unavailable';
  resultSnapshot: null;
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

export function parseMyInquiryDetail(value: unknown): MyInquiryDetail {
  const data = record(value);
  // The current public contract only defines unavailable snapshots; never invent evidence.
  if (data.snapshot_status !== 'unavailable' || data.result_snapshot !== null) fail();
  return {
    ...parseItem(data),
    explicitFilters: record(data.explicit_filters),
    resolutionNote: nullableText(data.resolution_note),
    reviewStartedAt: data.review_started_at === null ? null : timestamp(data.review_started_at),
    closedAt: data.closed_at === null ? null : timestamp(data.closed_at),
    snapshotStatus: 'unavailable',
    resultSnapshot: null,
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
