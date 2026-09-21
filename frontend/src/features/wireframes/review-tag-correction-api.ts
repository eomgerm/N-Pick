import { ApiClientError, fetchJson } from '@/lib/api/client';

export type TagCorrectionAction = 'APPROVE' | 'REJECT' | 'WITHDRAW';
export type TagCorrectionScope = 'SCENE' | 'CLIP';

export interface TagCorrectionOperation {
  action: TagCorrectionAction;
  scope: TagCorrectionScope;
  tagType: string;
  matchValue: string;
  displayName: string;
}

export interface TagCorrectionCandidate {
  feedbackId: string;
  created: number;
  evidenceIds: string[];
}

function fail(status = 200): never {
  throw new ApiClientError('invalid-response', status);
}

function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail();
  return value as Record<string, unknown>;
}

// TSID는 JavaScript 안전 정수를 넘는다. 문자열로 받아 문자열로 다룬다.
function identifier(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) fail();
  return value;
}

function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

export function parseTagCorrectionCandidate(value: unknown): TagCorrectionCandidate {
  const data = record(value);
  if (!Array.isArray(data.evidenceIds)) fail();
  return {
    feedbackId: identifier(data.feedbackId),
    created: integer(data.created),
    evidenceIds: data.evidenceIds.map(identifier),
  };
}

const errorMessages: Record<string, string> = {
  TAG_400_001: '변경안을 한 줄 이상 추가한 뒤 저장해 주세요.',
  TAG_400_002: '태그 유형이 올바르지 않습니다. 목록에서 다시 선택해 주세요.',
  TAG_400_003: '태그 값이 비어 있습니다. 검색에 쓰일 값을 입력해 주세요.',
  TAG_403_001: '검수자만 태그 변경안을 저장할 수 있습니다.',
  TAG_403_002: '담당 검수자만 태그 변경안을 저장할 수 있습니다.',
  TAG_404_001: '문의를 찾을 수 없습니다. 목록에서 최신 상태를 확인해 주세요.',
  TAG_409_001: '검수 중인 문의에서만 태그 변경안을 저장할 수 있습니다.',
  TAG_409_002: '태그 교정 또는 해석 교정으로 판정한 문의에서만 태그 변경안을 저장할 수 있습니다.',
};

/** 계약 오류 코드를 검수자용 안내로 바꾼다. 계약에 없는 실패는 null이며 공통 오류 표시에 맡긴다. */
export function tagCorrectionErrorMessage(error: unknown): string | null {
  if (!(error instanceof ApiClientError) || !error.code) return null;
  return errorMessages[error.code] ?? null;
}

export async function createTagCorrectionCandidate(
  feedbackId: string,
  operations: TagCorrectionOperation[],
  signal?: AbortSignal,
): Promise<TagCorrectionCandidate> {
  identifier(feedbackId);
  return parseTagCorrectionCandidate(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/tag-correction-candidate`, {
      method: 'POST',
      body: { operations },
      signal,
    }),
  );
}
