import { ApiClientError, fetchJson } from '@/lib/api/client';

export interface SceneExcludeCandidate {
  /** TSID 문자열. 정밀도가 깨지므로 숫자로 바꾸지 않는다. */
  searchRuleId: string;
  feedbackId: string;
  active: boolean;
}

// 계약 정본: docs/contracts/web-api.md §6.4 장면 제외 후보.
const errorMessages: Record<string, string> = {
  SRCH_400_211: '신고된 장면과 다른 장면은 제외 후보로 저장할 수 없습니다.',
  SRCH_400_212: '요청 형식이 올바르지 않습니다. 문의 상세를 다시 불러온 뒤 시도해 주세요.',
  SRCH_403_211: '검수자만 제외 후보를 저장할 수 있습니다.',
  SRCH_403_212: '이 문의의 담당 검수자만 제외 후보를 저장할 수 있습니다.',
  SRCH_404_211: '문의를 찾을 수 없습니다. 목록에서 최신 상태를 확인해 주세요.',
  SRCH_409_211: '검수 중인 문의가 아닙니다. 최신 상태를 다시 확인해 주세요.',
  SRCH_409_212: '장면 제외로 처리된 문의가 아닙니다. 처리 판정을 먼저 확인해 주세요.',
};

export function getSceneExcludeMessage(error: unknown): string {
  if (!(error instanceof ApiClientError)) {
    return '제외 후보를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }
  // 매핑 없는 코드라도 서버 평서체 원문을 그대로 노출하지 않는다 — 일반 안내로 대체한다.
  return errorMessages[error.code] ?? '제외 후보를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.';
}

function parseCandidate(value: unknown): SceneExcludeCandidate {
  const data = value as Record<string, unknown> | null;
  if (
    data === null ||
    typeof data !== 'object' ||
    typeof data.searchRuleId !== 'string' ||
    !data.searchRuleId ||
    typeof data.feedbackId !== 'string' ||
    !data.feedbackId ||
    typeof data.active !== 'boolean'
  ) {
    throw new ApiClientError('invalid-response', 200);
  }
  return { searchRuleId: data.searchRuleId, feedbackId: data.feedbackId, active: data.active };
}

/** 신규는 201, 멱등 재생은 200. 공통 client가 둘 다 성공으로 돌려준다. */
export async function createSceneExcludeCandidate(
  feedbackId: string,
  targetSceneId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<SceneExcludeCandidate> {
  return parseCandidate(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/scene-exclude-candidate`, {
      method: 'POST',
      body: { targetSceneId },
      idempotencyKey,
      signal,
    }),
  );
}
