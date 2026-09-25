import { ApiClientError, fetchJson } from '@/lib/api/client';
import type { InquiryResolution, InquiryStatus } from '@/features/wireframes/inquiry-state';

export const reviewTagTypes = [
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

export type ReviewTagType = (typeof reviewTagTypes)[number];
export type ReviewTagScope = 'SCENE' | 'CLIP';
export type TagCorrectionAction = 'APPROVE' | 'REJECT' | 'WITHDRAW';

export interface TagCorrectionOperation {
  action: TagCorrectionAction;
  scope: ReviewTagScope;
  tagType: ReviewTagType;
  matchValue: string;
  displayName: string;
}

export interface TagCorrectionCandidate {
  feedbackId: string;
  /** evidenceIds 길이. 자연 키로 재사용한 근거도 센다. */
  created: number;
  /** 이번 요청으로 실제로 새로 만든 근거 수(S15P21A501-317). 전부 재사용이면 0. */
  newlyCreated?: number;
  evidenceIds: string[];
}

/** 확정 전 대기 교정 후보 (S15P21A501-317). 새로고침 뒤 작성 중이던 교정을 복원하는 데 쓴다. */
export interface CorrectionCandidates {
  tags: Array<{
    evidenceId: string;
    taggingId: string;
    action: TagCorrectionAction;
    scope: ReviewTagScope;
    tagType: ReviewTagType;
    matchValue: string;
    displayName: string;
  }>;
  parsePatches: Array<{
    searchRuleId: string;
    condition: Record<string, unknown>;
    patch: Record<string, unknown>;
    replacesRuleId: string | null;
  }>;
  sceneExcludes: Array<{ searchRuleId: string; targetSceneId: string }>;
}

export type CorrectionCandidateTag = CorrectionCandidates['tags'][number];

export function correctionCandidatesQueryKey(feedbackId: string) {
  return ['correction-candidates', feedbackId] as const;
}

export interface ReviewInquiryScene {
  sceneId: string;
  clipId: string;
  clipTitle: string | null;
  startTimeMs: number;
  endTimeMs: number;
  pipelineRunId: string;
  processingNo: number;
}

export interface ReviewInquiryListItem {
  feedbackId: string;
  status: InquiryStatus;
  resolution: InquiryResolution | null;
  createdAt: string;
  queryText: string;
  sceneId: string;
  scene: ReviewInquiryScene;
  hasComment: boolean;
}

export interface ReviewInquiryList {
  items: ReviewInquiryListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  statusCounts: Record<InquiryStatus, number>;
}

export interface ReviewInquiryDetail extends ReviewInquiryListItem {
  resolutionNote: string | null;
  comment: string | null;
  resultRank: number;
  resultExplainJson: string | null;
  execution: {
    queryText: string;
    explicitFiltersJson: string | null;
    parsedQueryJson: string | null;
    resolverOutputJson: string | null;
    appliedRulesJson: string | null;
    appliedExcludesJson: string | null;
  };
  evidence: Array<{
    taggingId: string;
    tagType: ReviewTagType;
    matchValue: string;
    tagName: string;
    sources: string[];
    verifiedState: string | null;
    scope: ReviewTagScope;
  }>;
  history: {
    reviewedById: string | null;
    reviewerName: string | null;
    reviewerLoginId: string | null;
    reviewStartedAt: string | null;
    verifiedByExecutionId: string | null;
  };
}

function fail(status = 200): never {
  throw new ApiClientError('invalid-response', status);
}

function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}

function identifier(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) fail();
  return value;
}

function text(value: unknown): string {
  if (typeof value !== 'string') fail();
  return value;
}

function nullableText(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  return text(value);
}

function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

function positiveInteger(value: unknown): number {
  const parsed = integer(value);
  if (parsed === 0) fail();
  return parsed;
}

function instant(value: unknown): string {
  const parsed = text(value);
  if (Number.isNaN(Date.parse(parsed))) fail();
  return parsed;
}

function status(value: unknown): InquiryStatus {
  if (typeof value !== 'string') fail();
  const normalized = value.toLowerCase();
  if (normalized !== 'open' && normalized !== 'reviewing' && normalized !== 'closed') fail();
  return normalized;
}

function resolution(value: unknown): InquiryResolution | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') fail();
  const normalized = value.toLowerCase();
  if (
    normalized === 'exclude_scene' ||
    normalized === 'tag_correction' ||
    normalized === 'patch_parse'
  ) {
    return 'correction';
  }
  if (normalized !== 'no_action' && normalized !== 'deferred' && normalized !== 'correction') {
    fail();
  }
  return normalized;
}

function tagType(value: unknown): ReviewTagType {
  if (typeof value !== 'string' || !reviewTagTypes.includes(value as ReviewTagType)) fail();
  return value as ReviewTagType;
}

function tagScope(value: unknown): ReviewTagScope {
  if (value !== 'SCENE' && value !== 'CLIP') fail();
  return value;
}

function scene(value: unknown): ReviewInquiryScene {
  const data = record(value);
  const startTimeMs = integer(data.startTimeMs);
  const endTimeMs = integer(data.endTimeMs);
  if (endTimeMs <= startTimeMs) fail();
  return {
    sceneId: identifier(data.sceneId),
    clipId: identifier(data.clipId),
    clipTitle: nullableText(data.clipTitle),
    startTimeMs,
    endTimeMs,
    pipelineRunId: identifier(data.pipelineRunId),
    processingNo: positiveInteger(data.processingNo),
  };
}

function listItem(value: unknown): ReviewInquiryListItem {
  const data = record(value);
  const parsedScene = scene(data.scene);
  const sceneId = identifier(data.sceneId);
  if (sceneId !== parsedScene.sceneId || typeof data.hasComment !== 'boolean') fail();
  return {
    feedbackId: identifier(data.feedbackId),
    status: status(data.status),
    resolution: resolution(data.resolution),
    createdAt: instant(data.createdAt),
    queryText: text(data.queryText),
    sceneId,
    scene: parsedScene,
    hasComment: data.hasComment,
  };
}

export function parseReviewInquiryList(value: unknown): ReviewInquiryList {
  const data = record(value);
  const counts = record(data.statusCounts);
  if (!Array.isArray(data.items)) fail();
  return {
    items: data.items.map(listItem),
    page: integer(data.page),
    size: integer(data.size),
    totalElements: integer(data.totalElements),
    totalPages: integer(data.totalPages),
    statusCounts: {
      open: integer(counts.open),
      reviewing: integer(counts.reviewing),
      closed: integer(counts.closed),
    },
  };
}

export function parseReviewInquiryDetail(value: unknown): ReviewInquiryDetail {
  const data = record(value);
  const execution = record(data.execution);
  const history = record(data.history);
  if (!Array.isArray(data.evidence)) fail();
  const parsedScene = scene(data.scene);
  const sceneId = identifier(data.sceneId);
  if (sceneId !== parsedScene.sceneId) fail();
  return {
    feedbackId: identifier(data.feedbackId),
    status: status(data.status),
    resolution: resolution(data.resolution),
    resolutionNote: nullableText(data.resolutionNote),
    createdAt: instant(data.createdAt),
    comment: nullableText(data.comment),
    queryText: text(execution.queryText),
    sceneId,
    scene: parsedScene,
    hasComment: data.comment !== null && data.comment !== undefined,
    resultRank: positiveInteger(data.resultRank),
    resultExplainJson: nullableText(data.resultExplainJson),
    execution: {
      queryText: text(execution.queryText),
      explicitFiltersJson: nullableText(execution.explicitFiltersJson),
      parsedQueryJson: nullableText(execution.parsedQueryJson),
      resolverOutputJson: nullableText(execution.resolverOutputJson),
      appliedRulesJson: nullableText(execution.appliedRulesJson),
      appliedExcludesJson: nullableText(execution.appliedExcludesJson),
    },
    evidence: data.evidence.map((value) => {
      const item = record(value);
      return {
        taggingId: identifier(item.taggingId),
        tagType: tagType(item.tagType),
        matchValue: text(item.matchValue),
        tagName: text(item.tagName),
        sources: Array.isArray(item.sources) ? item.sources.map(text) : [],
        verifiedState: nullableText(item.verifiedState),
        scope: tagScope(item.scope),
      };
    }),
    history: {
      reviewedById: history.reviewedById == null ? null : identifier(history.reviewedById),
      reviewerName: nullableText(history.reviewerName),
      reviewerLoginId: nullableText(history.reviewerLoginId),
      reviewStartedAt: nullableText(history.reviewStartedAt),
      verifiedByExecutionId:
        history.verifiedByExecutionId == null ? null : identifier(history.verifiedByExecutionId),
    },
  };
}

export function parseCommaSeparatedTags(value: string): string[] {
  const seen = new Set<string>();
  return value
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => {
      if (!tag || seen.has(tag)) return false;
      seen.add(tag);
      return true;
    });
}

export function parseTagCorrectionCandidate(value: unknown): TagCorrectionCandidate {
  const data = record(value);
  if (!Array.isArray(data.evidenceIds)) fail();
  const evidenceIds = data.evidenceIds.map(identifier);
  const created = positiveInteger(data.created);
  if (created !== evidenceIds.length) fail();
  const parsed: TagCorrectionCandidate = {
    feedbackId: identifier(data.feedbackId),
    created,
    evidenceIds,
  };
  if (data.newlyCreated !== undefined) {
    const newlyCreated = integer(data.newlyCreated);
    if (newlyCreated > created) fail();
    parsed.newlyCreated = newlyCreated;
  }
  return parsed;
}

function tagAction(value: unknown): TagCorrectionAction {
  if (value !== 'APPROVE' && value !== 'REJECT' && value !== 'WITHDRAW') fail();
  return value;
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) fail();
  return value;
}

export function parseCorrectionCandidates(value: unknown): CorrectionCandidates {
  const data = record(value);
  return {
    tags: list(data.tags).map((value) => {
      const item = record(value);
      return {
        evidenceId: identifier(item.evidenceId),
        taggingId: identifier(item.taggingId),
        action: tagAction(item.action),
        scope: tagScope(item.scope),
        tagType: tagType(item.tagType),
        matchValue: text(item.matchValue),
        displayName: text(item.displayName),
      };
    }),
    parsePatches: list(data.parsePatches).map((value) => {
      const item = record(value);
      return {
        searchRuleId: identifier(item.searchRuleId),
        condition: record(item.condition),
        patch: record(item.patch),
        replacesRuleId: item.replacesRuleId === null ? null : identifier(item.replacesRuleId),
      };
    }),
    sceneExcludes: list(data.sceneExcludes).map((value) => {
      const item = record(value);
      return {
        searchRuleId: identifier(item.searchRuleId),
        targetSceneId: identifier(item.targetSceneId),
      };
    }),
  };
}

export async function getReviewInquiries(
  page: number,
  size: number,
  selectedStatus?: InquiryStatus,
  signal?: AbortSignal,
): Promise<ReviewInquiryList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (selectedStatus) query.set('status', selectedStatus.toUpperCase());
  const result = parseReviewInquiryList(
    await fetchJson<unknown>('/review/inquiries', { query, signal }),
  );
  // 서버는 size 를 1~100 으로 정규화한다. 고른 크기와 다르면 페이지 계산이 어긋나므로 받지 않는다.
  if (result.size !== size) fail();
  return result;
}

export async function getReviewInquiry(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<ReviewInquiryDetail> {
  identifier(feedbackId);
  return parseReviewInquiryDetail(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}`, { signal }),
  );
}

/** 담당 검수자가 이 신고에 쌓아 둔 확정 전 대기 교정 후보 전체를 읽는다 (S15P21A501-317). */
export async function getCorrectionCandidates(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<CorrectionCandidates> {
  identifier(feedbackId);
  return parseCorrectionCandidates(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/correction-candidates`, { signal }),
  );
}

export async function claimReviewInquiry(
  feedbackId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  await fetchJson<void>(`/review/inquiries/${feedbackId}/claim`, {
    method: 'POST',
    idempotencyKey,
    signal,
  });
}

/**
 * 담당 검수자가 선점을 풀어 문의를 미담당(OPEN) 상태로 되돌린다 (S15P21A501-289). 서버는 대기 중인
 * 교정 후보(태그·해석·장면 제외)를 모두 폐기하고 담당자·판정을 비운다. 응답 본문은 선점과 같은 모양이라
 * 선점처럼 읽지 않고, 화면은 상세를 다시 불러와 최신 상태를 따른다.
 */
export async function releaseReviewInquiry(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  await fetchJson<void>(`/review/inquiries/${feedbackId}/claim`, { method: 'DELETE', signal });
}

export async function resolveReviewInquiry(
  feedbackId: string,
  selectedResolution: InquiryResolution,
  note: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  await fetchJson<void>(`/review/inquiries/${feedbackId}/resolution`, {
    method: 'PUT',
    body: { resolution: selectedResolution, note: note.trim() || null },
    signal,
  });
}

export async function createTagCorrectionCandidate(
  feedbackId: string,
  operations: TagCorrectionOperation[],
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<TagCorrectionCandidate> {
  identifier(feedbackId);
  if (operations.length === 0) fail(400);
  return parseTagCorrectionCandidate(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/tag-correction-candidate`, {
      method: 'POST',
      body: { operations },
      idempotencyKey,
      signal,
    }),
  );
}

/**
 * 이 신고의 대기 중인 태그 교정 근거 하나만 폐기한다 (S15P21A501-309). 확정된 근거는 건드리지 않고,
 * 이미 없는 근거를 다시 지워도 서버는 성공으로 답한다(멱등).
 */
export async function discardTagCorrectionCandidateEvidence(
  feedbackId: string,
  evidenceId: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  identifier(evidenceId);
  await fetchJson<unknown>(
    `/review/inquiries/${feedbackId}/tag-correction-candidate/${evidenceId}`,
    { method: 'DELETE', signal },
  );
}
