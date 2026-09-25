import type { ReviewInquiryDetail } from '@/features/wireframes/review-inquiry-api';
import { ApiClientError } from '@/lib/api/error';

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

// 검수 취소 오류 코드는 선점(FEEDBACK_*)과 같은 체계를 따르므로 HTTP 상태로 안내를 고른다.
// 알 수 없는 상태는 undefined 를 돌려 서버 문구(ApiErrorNotice 기본값)를 그대로 쓴다.
export function getReleaseErrorMessage(error: unknown): string | undefined {
  if (!(error instanceof ApiClientError)) return undefined;
  if (error.status === 403) return '이 문의의 담당자만 검수를 취소할 수 있습니다.';
  if (error.status === 409) {
    return '이미 검수 중인 상태가 아닙니다. 최신 상태를 다시 확인해 주세요.';
  }
  if (error.status === 404) {
    return '문의가 더 이상 존재하지 않습니다. 목록에서 최신 문의를 확인해 주세요.';
  }
  return undefined;
}
