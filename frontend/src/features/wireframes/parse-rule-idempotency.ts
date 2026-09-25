import type { ParseRuleCandidateBody } from '@/features/wireframes/review-parse-rule-api';

/**
 * 규칙 내용으로부터 결정론적 멱등성 키를 만든다. 같은 내용은 매번 같은 키가 되어, 중간 실패 뒤
 * 재시도해도 이미 만든 후보를 새로 만들지 않고 서버가 기존 후보를 그대로 돌려준다 — 재시도마다 후보가
 * 쌓여 신고당 후보 상한(SRCH_409_204)에 닿는 것을 막는다.
 */
export async function ruleIdempotencyKey(
  feedbackId: string,
  body: ParseRuleCandidateBody,
): Promise<string> {
  const bytes = new TextEncoder().encode(`${feedbackId}:${JSON.stringify(body)}`);
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join(
    '',
  );
  // 서버 상한은 64자 (docs/contracts/web-api.md); 'parse:' 접두 + 48자 해시로 여유 있게 맞춘다.
  return `parse:${hex.slice(0, 48)}`;
}
