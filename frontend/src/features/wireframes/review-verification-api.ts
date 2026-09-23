import { ApiClientError, fetchJson } from '@/lib/api/client';
import type {
  SearchKeywordOrigin,
  SearchMatchedKeyword,
} from '@/features/wireframes/search-api-contract';

export type DroppedReason = 'approved_scene_exclusion' | 'false_hit_guard' | 'score_drop';

export interface VerificationScene {
  sceneId: string;
  clipId: string;
  displayName: string | null;
  sceneDescription: string | null;
  startTimeMs: number;
  endTimeMs: number;
}

export interface VerificationResult {
  executionId: string;
  enteredScenes: Array<VerificationScene & { matchedKeywords: SearchMatchedKeyword[] }>;
  droppedScenes: Array<VerificationScene & { reason: DroppedReason }>;
  verificationRuleSet: string[];
}

// SRCH_409_231(상태 검사)과 SRCH_409_232(후보 존재 검사)는 서로 다른 검사다.
export const verificationErrorMessages: Record<string, string> = {
  SRCH_403_231: '이 문의의 담당 검수자만 검증할 수 있습니다.',
  SRCH_404_231: '문의를 찾을 수 없습니다. 목록에서 최신 상태를 확인해 주세요.',
  SRCH_409_231: '검수 중인 문의가 아닙니다. 검수를 시작한 뒤 다시 검증해 주세요.',
  SRCH_409_232: '대기 중인 교정 후보가 없습니다. 후보를 먼저 저장한 뒤 검증해 주세요.',
};

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

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) fail();
  return value;
}

function nullableText(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value !== 'string' || !value.trim()) fail();
  return value;
}

function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

function scene(value: Record<string, unknown>): VerificationScene {
  const startTimeMs = integer(value.start_time_ms);
  const endTimeMs = integer(value.end_time_ms);
  if (endTimeMs <= startTimeMs) fail();
  return {
    sceneId: identifier(value.scene_id),
    clipId: identifier(value.clip_id),
    displayName: nullableText(value.display_name),
    sceneDescription: nullableText(value.scene_description),
    startTimeMs,
    endTimeMs,
  };
}

function enteredKeywords(value: unknown): SearchMatchedKeyword[] {
  const reason = record(value);
  const match = record(reason.match);
  return list(match.matched_keywords).map((value) => {
    const matched = record(value);
    if (typeof matched.keyword !== 'string' || !matched.keyword.trim()) fail();
    if (matched.origin !== 'user' && matched.origin !== 'expanded') fail();
    return {
      keyword: matched.keyword,
      origin: matched.origin as SearchKeywordOrigin,
    };
  });
}

function droppedReason(value: unknown): DroppedReason {
  if (value !== 'approved_scene_exclusion' && value !== 'false_hit_guard' && value !== 'score_drop')
    fail();
  return value;
}

export function parseVerificationResult(value: unknown): VerificationResult {
  const data = record(value);
  return {
    executionId: identifier(data.execution_id),
    enteredScenes: list(data.entered_scenes).map((item) => {
      const entered = record(item);
      return { ...scene(entered), matchedKeywords: enteredKeywords(entered.reason) };
    }),
    droppedScenes: list(data.dropped_scenes).map((item) => {
      const dropped = record(item);
      return { ...scene(dropped), reason: droppedReason(dropped.reason) };
    }),
    verificationRuleSet: list(data.verification_rule_set).map(identifier),
  };
}

export async function verifyCorrectionCandidates(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<VerificationResult> {
  identifier(feedbackId);
  return parseVerificationResult(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/verify`, { method: 'POST', signal }),
  );
}
