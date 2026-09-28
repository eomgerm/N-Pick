import type { InquiryStatus } from '@/features/wireframes/inquiry-state';
import { ApiClientError, fetchJson } from '@/lib/api/client';
import { createIdempotencyKey } from '@/lib/api/idempotency';
import { validateInquiryComment } from '@/features/wireframes/input-validation';

export interface InquirySubmissionSnapshot {
  resultId: string;
  comment: string;
}

export interface InquirySubmission {
  key: string;
  snapshot: Readonly<InquirySubmissionSnapshot>;
}

export interface InquirySubmissionResult {
  inquiryId: string;
  status: InquiryStatus;
}

function normalizeResultId(resultId: string): string {
  if (!/^[1-9]\d*$/.test(resultId)) {
    throw new TypeError('Inquiry resultId must be a positive decimal string.');
  }
  return resultId;
}

export function normalizeInquiryComment(comment: string): string {
  return comment.trim();
}

export function createInquirySubmission(
  resultId: string,
  comment: string,
  keyFactory: () => string = createIdempotencyKey,
): InquirySubmission {
  return {
    key: keyFactory(),
    snapshot: Object.freeze({
      resultId: normalizeResultId(resultId),
      comment: normalizeInquiryComment(comment),
    }),
  };
}

export function isSameInquiryRequest(
  submission: InquirySubmission,
  resultId: string,
  comment: string,
): boolean {
  return (
    submission.snapshot.resultId === resultId &&
    submission.snapshot.comment === normalizeInquiryComment(comment)
  );
}

export function parseInquiryResponse(value: unknown): InquirySubmissionResult {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiClientError('invalid-response', 200);
  }

  const payload = value as Record<string, unknown>;
  const inquiryId =
    typeof payload.feedbackId === 'string'
      ? payload.feedbackId
      : typeof payload.feedbackId === 'number' &&
          Number.isSafeInteger(payload.feedbackId) &&
          payload.feedbackId > 0
        ? String(payload.feedbackId)
        : undefined;

  const status =
    payload.status === 'OPEN'
      ? 'open'
      : payload.status === 'REVIEWING'
        ? 'reviewing'
        : payload.status === 'CLOSED'
          ? 'closed'
          : undefined;

  if (inquiryId === undefined || !/^[1-9]\d*$/.test(inquiryId) || status === undefined) {
    throw new ApiClientError('invalid-response', 200);
  }

  return { inquiryId, status };
}

export async function submitInquiry(
  submission: InquirySubmission,
  signal?: AbortSignal,
): Promise<InquirySubmissionResult> {
  const { resultId, comment } = submission.snapshot;
  const validationError = validateInquiryComment(comment);
  if (validationError) throw new ApiClientError('api', 0, { message: validationError });
  const response = await fetchJson<unknown>(`/search/results/${resultId}/inquiries`, {
    method: 'POST',
    body: comment ? { comment } : {},
    idempotencyKey: submission.key,
    signal,
  });

  return parseInquiryResponse(response);
}
