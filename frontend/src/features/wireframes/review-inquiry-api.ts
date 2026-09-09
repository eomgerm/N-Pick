import { ApiClientError, fetchJson } from '@/lib/api/client';
import type { InquiryResolution, InquiryStatus } from '@/features/wireframes/inquiry-state';

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
    tagName: string;
    source: string | null;
    verifiedState: string | null;
    scope: string;
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
    normalized !== 'exclude_scene' &&
    normalized !== 'no_action' &&
    normalized !== 'deferred' &&
    normalized !== 'tag_correction' &&
    normalized !== 'patch_parse'
  ) {
    fail();
  }
  return normalized;
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
        tagName: text(item.tagName),
        source: nullableText(item.source),
        verifiedState: nullableText(item.verifiedState),
        scope: text(item.scope),
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

export async function getReviewInquiries(
  page: number,
  selectedStatus?: InquiryStatus,
  signal?: AbortSignal,
): Promise<ReviewInquiryList> {
  const query = new URLSearchParams({ page: String(page), size: '10' });
  if (selectedStatus) query.set('status', selectedStatus.toUpperCase());
  return parseReviewInquiryList(await fetchJson<unknown>('/review/inquiries', { query, signal }));
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
