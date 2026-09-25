import type { ReviewInquiryDetail } from '@/features/wireframes/review-inquiry-api';

/** 검수 취소는 검수 중인 문의를 맡은 담당자 본인에게만 보인다 (S15P21A501-289). */
export function canReleaseClaim(
  inquiry: Pick<ReviewInquiryDetail, 'status' | 'history'>,
  memberLoginId: string,
): boolean {
  return (
    inquiry.status === 'reviewing' &&
    Boolean(memberLoginId) &&
    inquiry.history.reviewerLoginId === memberLoginId
  );
}

// 오류 코드로만 안내를 고른다. COMM_403(역할·CSRF 등 보안 계층)은 담당자 문제가 아니므로
// 여기 없고, 서버 문구(ApiErrorNotice 기본값)로 떨어진다.
export const releaseErrorMessages: Record<string, string> = {
  FEEDBACK_403_002: '이 문의의 담당자만 검수를 취소할 수 있습니다.',
  FEEDBACK_404_002: '문의가 더 이상 존재하지 않습니다. 목록에서 최신 문의를 확인해 주세요.',
  FEEDBACK_409_003: '이미 검수가 취소되었거나 문의 상태가 바뀌었습니다. 최신 상태를 확인해 주세요.',
};

// 검수 취소가 진행 중인 요청과 엇갈려 방금 폐기된 후보를 다시 만들지 않도록, 문의의 교정·판정을
// 바꾸는 요청이 하나라도 진행 중이면 취소를 잠근다. 각 요청은 [키, feedbackId] 로 mutationKey 를 단다.
export const correctionMutationKeys = [
  'parse-patch-save',
  'tag-candidate-change',
  'scene-exclude-change',
  'resolution-save',
  'verification-run',
  'confirm-correction',
] as const;

export function isCorrectionMutation(
  mutationKey: readonly unknown[] | undefined,
  feedbackId: string,
): boolean {
  if (!mutationKey || mutationKey[1] !== feedbackId) return false;
  return (correctionMutationKeys as readonly unknown[]).includes(mutationKey[0]);
}
