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
  created: number;
  evidenceIds: string[];
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
  return {
    feedbackId: identifier(data.feedbackId),
    created,
    evidenceIds,
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
  signal?: AbortSignal,
): Promise<TagCorrectionCandidate> {
  identifier(feedbackId);
  if (operations.length === 0) fail(400);
  return parseTagCorrectionCandidate(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/tag-correction-candidate`, {
      method: 'POST',
      body: { operations },
      signal,
    }),
  );
}
