import { ApiClientError } from '@/lib/api/error';
import { validateSearchQuery } from '@/features/wireframes/input-validation';

import { isCalendarDate, validateDateRange } from '@/features/wireframes/date-range';
import type { DateRange } from '@/features/wireframes/date-range';
import type { SearchNavigationInput } from '@/features/wireframes/search-navigation';

export const SEARCH_API_PATH = '/search';
export const SEARCH_RESULT_LIMIT = 10;
// 1글자 질의는 형태소 충돌로 무관 결과를 대량 반환한다("비"→"비해", "불"→"불다"). BE @Size(min=2) 와 같은 하한이다.
export { SEARCH_QUERY_MIN_LENGTH } from '@/features/wireframes/input-validation';

export interface InclusiveDateFilter {
  from: string;
  to: string;
}

export interface SearchExplicitFilters {
  broadcast_date?: InclusiveDateFilter;
  filmed_date?: InclusiveDateFilter;
}

export interface SearchRequestBody {
  query: string;
  explicit_filters: SearchExplicitFilters;
  // 0-based 결과 페이지. 생략하면 서버가 첫 페이지(0)로 본다. 더보기가 다음 페이지를 요청할 때만 싣는다.
  page?: number;
}

export type SearchExecutionStatus = 'succeeded' | 'degraded';
export type SearchDegradedReason =
  'resolver_fallback' | 'dense_unavailable' | 'snapshot_save_failed';
export type SearchQueryResolutionStatus = 'resolved' | 'fallback';
export type SearchGuardReason =
  'explicit_date_conflict' | 'approved_incident_conflict' | 'approved_scene_exclusion';
export type SearchShortageReason = 'candidate_pool_exhausted' | 'guard_excluded';
export type SearchInformationStatus = 'verified' | 'unverified' | 'unknown';
export type SearchEvidenceStatus = Exclude<SearchInformationStatus, 'unknown'>;
export type SearchEvidenceField = 'caption' | 'ocr' | 'transcript' | 'tag';
export type SearchShotType = 'anchor' | 'interview' | 'b_roll' | 'unknown';

/**
 * 키워드 칩이 어디서 온 말인가. `user` 는 사용자가 직접 친 말, `expanded` 는 AI 해석기가 넓힌 말이다 (F-05·F-07).
 *
 * `unknown` 은 구분을 남기지 않던 시절에 저장된 기록을 복원할 때만 온다 — 그때 사용자 입력어로 보여 주면 없던 사실을 만들어 내는 것이다 (FRD §7.2).
 */
export type SearchKeywordOrigin = 'user' | 'expanded' | 'unknown';

export interface SearchMatchedKeyword {
  keyword: string;
  origin: SearchKeywordOrigin;
}

export interface SearchDateInformation {
  value: string | null;
  verificationStatus: SearchInformationStatus;
}

export interface SearchMatchEvidence {
  field: SearchEvidenceField;
  value: string | null;
  source: string;
  verificationStatus: SearchEvidenceStatus;
}

export interface SearchSceneResponse {
  searchResultId: string | null;
  sceneId: string;
  clipId: string;
  rank: number;
  displayName: string | null;
  sceneDescription: string | null;
  startTimeMs: number;
  endTimeMs: number;
  broadcastDate: SearchDateInformation;
  filmedDate: SearchDateInformation;
  shotType: SearchShotType;
  sceneType: string | null;
  matchedKeywords: SearchMatchedKeyword[];
  matchEvidence: SearchMatchEvidence[];
}

export interface SearchGuardSummary {
  excludedResultCount: number;
  reasons: SearchGuardReason[];
}

export interface SearchResponse {
  searchExecutionId: string | null;
  status: SearchExecutionStatus;
  degradedReasons: SearchDegradedReason[];
  queryResolutionStatus: SearchQueryResolutionStatus;
  hasAppliedReviewRule: boolean;
  guardSummary: SearchGuardSummary;
  shortageReasons: SearchShortageReason[];
  results: SearchSceneResponse[];
  // 다음 페이지가 있으면 참. 더보기 버튼 노출을 결정한다 (S15P21A501-251).
  hasNext: boolean;
}

function toInclusiveFilter(range: DateRange): InclusiveDateFilter | undefined {
  return range.from && range.to ? { from: range.from, to: range.to } : undefined;
}

export function createSearchRequestBody({
  query,
  broadcast,
  filming,
}: SearchNavigationInput): SearchRequestBody | null {
  const normalizedQuery = query.trim();
  if (validateSearchQuery(query) || validateDateRange(broadcast) || validateDateRange(filming))
    return null;

  const explicitFilters: SearchExplicitFilters = {};
  const broadcastDate = toInclusiveFilter(broadcast);
  const filmedDate = toInclusiveFilter(filming);
  if (broadcastDate) explicitFilters.broadcast_date = broadcastDate;
  if (filmedDate) explicitFilters.filmed_date = filmedDate;

  return { query: normalizedQuery, explicit_filters: explicitFilters };
}

function invalidResponse(status: number): never {
  throw new ApiClientError('invalid-response', status);
}

function readRecord(value: unknown, status: number): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return invalidResponse(status);
  }
  return value as Record<string, unknown>;
}

function readNonEmptyString(value: unknown, status: number): string {
  if (typeof value !== 'string' || !value.trim()) return invalidResponse(status);
  return value;
}

function readNullableString(value: unknown, status: number): string | null {
  return value === null ? null : readNonEmptyString(value, status);
}

function readId(value: unknown, status: number, isNullable = false): string | null {
  if (isNullable && value === null) return null;
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : invalidResponse(status);
}

function readNonNegativeInteger(value: unknown, status: number): number {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? (value as number)
    : invalidResponse(status);
}

function readPositiveInteger(value: unknown, status: number): number {
  const parsed = readNonNegativeInteger(value, status);
  return parsed > 0 ? parsed : invalidResponse(status);
}

function readBoolean(value: unknown, status: number): boolean {
  return typeof value === 'boolean' ? value : invalidResponse(status);
}

function readEnum<Value extends string>(
  value: unknown,
  allowed: readonly Value[],
  status: number,
): Value {
  return typeof value === 'string' && allowed.includes(value as Value)
    ? (value as Value)
    : invalidResponse(status);
}

function readUniqueEnumList<Value extends string>(
  value: unknown,
  allowed: readonly Value[],
  status: number,
): Value[] {
  if (!Array.isArray(value)) return invalidResponse(status);
  const parsed = value.map((item) => readEnum(item, allowed, status));
  return new Set(parsed).size === parsed.length ? parsed : invalidResponse(status);
}

function parseDateInformation(value: unknown, status: number): SearchDateInformation {
  const payload = readRecord(value, status);
  const date = payload.value === null ? null : readNonEmptyString(payload.value, status);
  const verificationStatus = readEnum(
    payload.verification_status,
    ['verified', 'unverified', 'unknown'] as const,
    status,
  );
  if ((date === null) !== (verificationStatus === 'unknown')) return invalidResponse(status);
  if (date !== null && !isCalendarDate(date)) return invalidResponse(status);
  return { value: date, verificationStatus };
}

const KEYWORD_ORIGINS = ['user', 'expanded', 'unknown'] as const;

function parseMatchedKeywords(value: unknown, status: number): SearchMatchedKeyword[] {
  if (!Array.isArray(value)) return invalidResponse(status);
  return value.map((item) => {
    const payload = readRecord(item, status);
    return {
      keyword: readNonEmptyString(payload.keyword, status),
      origin: readEnum(payload.origin, KEYWORD_ORIGINS, status),
    };
  });
}

function parseMatchEvidence(
  value: unknown,
  status: number,
  isHistory: boolean,
): SearchMatchEvidence {
  const payload = readRecord(value, status);
  return {
    field: readEnum(payload.field, ['caption', 'ocr', 'transcript', 'tag'] as const, status),
    value: isHistory
      ? readNullableString(payload.value, status)
      : readNonEmptyString(payload.value, status),
    source: readNonEmptyString(payload.source, status),
    verificationStatus: readEnum(
      payload.verification_status,
      ['verified', 'unverified'] as const,
      status,
    ),
  };
}

function parseScene(value: unknown, status: number, isHistory: boolean): SearchSceneResponse {
  const payload = readRecord(value, status);
  const startTimeMs = readNonNegativeInteger(payload.start_time_ms, status);
  const endTimeMs = readPositiveInteger(payload.end_time_ms, status);
  if (endTimeMs <= startTimeMs) return invalidResponse(status);
  if (!Array.isArray(payload.match_evidence) || payload.match_evidence.length === 0) {
    return invalidResponse(status);
  }

  return {
    searchResultId: readId(payload.search_result_id, status, true),
    sceneId: readId(payload.scene_id, status) as string,
    clipId: readId(payload.clip_id, status) as string,
    rank: readPositiveInteger(payload.rank, status),
    displayName: readNullableString(payload.display_name, status),
    sceneDescription: readNullableString(payload.scene_description, status),
    startTimeMs,
    endTimeMs,
    broadcastDate: parseDateInformation(payload.broadcast_date, status),
    filmedDate: parseDateInformation(payload.filmed_date, status),
    shotType: readEnum(
      payload.shot_type,
      ['anchor', 'interview', 'b_roll', 'unknown'] as const,
      status,
    ),
    sceneType: readNullableString(payload.scene_type, status),
    matchedKeywords: parseMatchedKeywords(payload.matched_keywords, status),
    matchEvidence: payload.match_evidence.map((item) =>
      parseMatchEvidence(item, status, isHistory),
    ),
  };
}

export function parseSearchResponse(value: unknown, httpStatus = 200): SearchResponse {
  return parseSearchPayload(value, httpStatus, false);
}

/** 과거 근거의 null은 기록 부재로 보존합니다. 실시간 검색의 검증은 완화하지 않습니다. */
export function parseSearchSnapshot(value: unknown, httpStatus = 200): SearchResponse {
  return parseSearchPayload(value, httpStatus, true);
}

function parseSearchPayload(
  value: unknown,
  httpStatus: number,
  isHistory: boolean,
): SearchResponse {
  const payload = readRecord(value, httpStatus);
  const searchExecutionId = readId(payload.search_execution_id, httpStatus, true);
  const status = readEnum(payload.status, ['succeeded', 'degraded'] as const, httpStatus);
  const degradedReasons = readUniqueEnumList(
    payload.degraded_reasons,
    ['resolver_fallback', 'dense_unavailable', 'snapshot_save_failed'] as const,
    httpStatus,
  );
  const queryResolutionStatus = readEnum(
    payload.query_resolution_status,
    ['resolved', 'fallback'] as const,
    httpStatus,
  );
  const guardPayload = readRecord(payload.guard_summary, httpStatus);
  const guardSummary: SearchGuardSummary = {
    excludedResultCount: readNonNegativeInteger(guardPayload.excluded_result_count, httpStatus),
    reasons: readUniqueEnumList(
      guardPayload.reasons,
      ['explicit_date_conflict', 'approved_incident_conflict', 'approved_scene_exclusion'] as const,
      httpStatus,
    ),
  };
  const shortageReasons = readUniqueEnumList(
    payload.shortage_reasons,
    ['candidate_pool_exhausted', 'guard_excluded'] as const,
    httpStatus,
  );
  if (!Array.isArray(payload.results) || payload.results.length > SEARCH_RESULT_LIMIT) {
    return invalidResponse(httpStatus);
  }
  const results = payload.results.map((item) => parseScene(item, httpStatus, isHistory));

  const isEphemeral = degradedReasons.includes('snapshot_save_failed');
  if ((status === 'succeeded') !== (degradedReasons.length === 0)) {
    return invalidResponse(httpStatus);
  }
  if ((queryResolutionStatus === 'fallback') !== degradedReasons.includes('resolver_fallback')) {
    return invalidResponse(httpStatus);
  }
  if ((searchExecutionId === null) !== isEphemeral) return invalidResponse(httpStatus);
  const isShortage = results.length < SEARCH_RESULT_LIMIT;
  if (
    (guardSummary.excludedResultCount === 0) !== (guardSummary.reasons.length === 0) ||
    isShortage !== shortageReasons.length > 0
  ) {
    return invalidResponse(httpStatus);
  }

  const sceneIds = new Set<string>();
  const searchResultIds = new Set<string>();
  results.forEach((result, index) => {
    if (result.rank !== index + 1 || (result.searchResultId === null) !== isEphemeral) {
      return invalidResponse(httpStatus);
    }
    if (sceneIds.has(result.sceneId)) return invalidResponse(httpStatus);
    sceneIds.add(result.sceneId);
    if (result.searchResultId !== null) {
      if (searchResultIds.has(result.searchResultId)) return invalidResponse(httpStatus);
      searchResultIds.add(result.searchResultId);
    }
  });

  return {
    searchExecutionId,
    status,
    degradedReasons,
    queryResolutionStatus,
    hasAppliedReviewRule: readBoolean(payload.has_applied_review_rule, httpStatus),
    guardSummary,
    shortageReasons,
    results,
    // 더보기는 실시간 검색 전용이다. 스냅샷(과거 기록)에는 has_next 가 없으므로 항상 false 로 둔다.
    hasNext: isHistory ? false : readBoolean(payload.has_next, httpStatus),
  };
}
