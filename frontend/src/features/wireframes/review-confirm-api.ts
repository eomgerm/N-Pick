import { ApiClientError, fetchJson } from '@/lib/api/client';

// CONFIRM_409_003(검증 이후 상태 변경)과 CONFIRM_409_004(대상 장면 소실)는 서로 다른 검사이며,
// 둘 다 재검증으로만 풀 수 있다.
export const confirmErrorMessages: Record<string, string> = {
  CONFIRM_403_001: '검수자만 교정을 확정할 수 있습니다.',
  CONFIRM_403_002: '이 문의의 담당 검수자만 교정을 확정할 수 있습니다.',
  CONFIRM_404_001: '문의를 찾을 수 없습니다. 목록에서 최신 상태를 확인해 주세요.',
  CONFIRM_404_002: '확정할 검증 실행이 없습니다. 후보를 다시 검증해 주세요.',
  CONFIRM_409_001: '검수 중인 문의가 아닙니다. 검수를 시작한 뒤 다시 확정해 주세요.',
  CONFIRM_409_002: '태그·해석 교정으로 처리된 문의가 아닙니다. 처리 판정을 먼저 저장해 주세요.',
  CONFIRM_409_003: '검증 이후 관련 상태가 바뀌었습니다. 다시 검증해 주세요.',
  CONFIRM_409_004: '대상 장면이 재처리로 사라졌습니다. 다시 검증해 주세요.',
};

function identifier(value: string): string {
  if (!/^[1-9]\d*$/.test(value)) throw new ApiClientError('invalid-response', 0);
  return value;
}

export async function confirmCorrection(
  feedbackId: string,
  executionId: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  await fetchJson<void>(`/review/inquiries/${feedbackId}/confirm`, {
    method: 'POST',
    body: { executionId: identifier(executionId) },
    signal,
  });
}
