import { ApiClientError, fetchJson } from '@/lib/api/client';
import { isCalendarDate } from '@/features/wireframes/date-range';
import {
  parseSearchSnapshot,
  SEARCH_RESULT_LIMIT,
  type SearchExecutionStatus,
  type SearchExplicitFilters,
  type SearchResponse,
} from '@/features/wireframes/search-api-contract';

interface RepresentativeResult {
  searchResultId: string;
  sceneId: string;
  clipId: string;
  displayName: string | null;
  sceneDescription: string | null;
  startTimeMs: number;
  endTimeMs: number;
  rank: number;
}

export interface MySearchHistoryItem {
  searchExecutionId: string;
  queryText: string;
  explicitFilters: SearchExplicitFilters | null;
  createdAt: string;
  status: SearchExecutionStatus;
  snapshotStatus: 'available' | 'unavailable';
  resultCount: number | null;
  representativeResult: RepresentativeResult | null;
}

export interface MySearchHistoryPage {
  items: MySearchHistoryItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface MySearchHistoryDetail extends MySearchHistoryItem {
  searchSnapshot: SearchResponse | null;
}

export const mySearchHistoryKeys = {
  /** 이 사용자의 기록 쿼리 전체. 한 건을 지워도 뒷 페이지 구성과 총계가 밀려 목록을 통째로 다시 읽어야 한다. */
  all: (memberId: string) => ['my-search-history', memberId] as const,
  list: (memberId: string, page: number) => ['my-search-history', memberId, 'list', page] as const,
  detail: (memberId: string, executionId: string) =>
    ['my-search-history', memberId, 'detail', executionId] as const,
};

function fail(): never {
  throw new ApiClientError('invalid-response', 200);
}
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) fail();
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

function parseFilters(value: unknown): SearchExplicitFilters | null {
  if (value === null) return null;
  const filters = record(value);
  for (const key of ['broadcast_date', 'filmed_date'] as const) {
    if (!Object.hasOwn(filters, key)) continue;
    const range = record(filters[key]);
    const from = text(range.from);
    const to = text(range.to);
    if (!isCalendarDate(from) || !isCalendarDate(to) || from > to) fail();
  }
  return filters as SearchExplicitFilters;
}

function parseRepresentative(value: unknown): RepresentativeResult | null {
  if (value === null) return null;
  const result = record(value);
  const startTimeMs = integer(result.start_time_ms);
  const endTimeMs = integer(result.end_time_ms, 1);
  const rank = integer(result.rank, 1);
  if (endTimeMs <= startTimeMs || rank !== 1) fail();
  return {
    searchResultId: id(result.search_result_id),
    sceneId: id(result.scene_id),
    clipId: id(result.clip_id),
    displayName: nullableText(result.display_name),
    sceneDescription: nullableText(result.scene_description),
    startTimeMs,
    endTimeMs,
    rank,
  };
}

function parseItem(value: unknown): MySearchHistoryItem {
  const item = record(value);
  const explicitFilters = parseFilters(item.explicit_filters);
  const resultCount = item.result_count === null ? null : integer(item.result_count);
  const representativeResult = parseRepresentative(item.representative_result);
  const createdAt = text(item.created_at);
  if (!Number.isFinite(Date.parse(createdAt))) fail();
  if (item.status !== 'succeeded' && item.status !== 'degraded') fail();
  if (item.snapshot_status === 'unavailable') {
    if (resultCount !== null || representativeResult !== null) fail();
  } else if (item.snapshot_status === 'available') {
    if (
      explicitFilters === null ||
      resultCount === null ||
      resultCount > SEARCH_RESULT_LIMIT ||
      (resultCount === 0) !== (representativeResult === null)
    )
      fail();
  } else fail();
  return {
    searchExecutionId: id(item.search_execution_id),
    queryText: text(item.query_text),
    explicitFilters,
    createdAt,
    status: item.status,
    snapshotStatus: item.snapshot_status,
    resultCount,
    representativeResult,
  };
}

export function parseMySearchHistoryPage(value: unknown): MySearchHistoryPage {
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
    data.has_next !== page + 1 < totalPages ||
    data.items.length !== Math.min(size, Math.max(0, totalElements - page * size))
  )
    fail();
  const items = data.items.map(parseItem);
  if (new Set(items.map((item) => item.searchExecutionId)).size !== items.length) fail();
  return { items, page, size, totalElements, totalPages, hasNext: data.has_next };
}

export function parseMySearchHistoryDetail(value: unknown): MySearchHistoryDetail {
  const data = record(value);
  const item = parseItem(data);
  if (item.snapshotStatus === 'unavailable') {
    if (data.search_snapshot !== null) fail();
    return { ...item, searchSnapshot: null };
  }
  const searchSnapshot = parseSearchSnapshot(data.search_snapshot);
  if (
    searchSnapshot.searchExecutionId !== item.searchExecutionId ||
    searchSnapshot.status !== item.status ||
    searchSnapshot.results.length !== item.resultCount
  )
    fail();
  const first = searchSnapshot.results[0];
  if (
    item.representativeResult &&
    Object.entries(item.representativeResult).some(
      ([key, value]) => first[key as keyof typeof first] !== value,
    )
  )
    fail();
  return { ...item, searchSnapshot };
}

export async function getMySearchHistory(page: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(integer(page)), size: '10' });
  const result = parseMySearchHistoryPage(
    await fetchJson<unknown>('/search/history', { query, signal }),
  );
  if (result.page !== page || result.size !== 10) fail();
  return result;
}

export async function getMySearchHistoryDetail(executionId: string, signal?: AbortSignal) {
  const result = parseMySearchHistoryDetail(
    await fetchJson<unknown>(`/search/history/${id(executionId)}`, { signal }),
  );
  if (result.searchExecutionId !== executionId) fail();
  return result;
}

/**
 * 기록 하나를 내 목록에서 지운다 (S15P21A501-276 계약).
 *
 * 서버는 행을 지우지 않고 목록·상세에서만 감추지만, 그 사실은 화면에 드러내지 않는다 — 사용자에게는 삭제다.
 *
 * 404 를 오류로 올리지 않는다. 서버가 멱등이라 본인 기록을 다시 지우면 200 이고, 404 는 남의 기록이나 없는 id —
 * 자기 목록에서 고른 항목으로는 정상적으로 나오지 않는 응답이다. 그래도 목록이 낡았을 때 닿을 수 있고, 그 경우
 * 사용자가 할 수 있는 일은 목록을 다시 읽는 것뿐이라 호출부가 두 경우를 구분할 이유가 없다.
 */
export async function deleteMySearchHistory(executionId: string, signal?: AbortSignal) {
  try {
    await fetchJson<unknown>(`/search/history/${id(executionId)}`, { method: 'DELETE', signal });
  } catch (error) {
    if (!(error instanceof ApiClientError) || error.status !== 404) throw error;
  }
}
