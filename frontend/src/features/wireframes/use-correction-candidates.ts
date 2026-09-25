'use client';

import { useQuery } from '@tanstack/react-query';

import {
  correctionCandidatesQueryKey,
  getCorrectionCandidates,
} from '@/features/wireframes/review-inquiry-api';

/**
 * 이 신고의 대기 교정 후보 (S15P21A501-317). 태그 교정·장면 제외·검증 패널이 같은 키를 공유하고,
 * 후보를 바꾸는 요청이 성공하면 이 키를 무효화해 서버 상태를 다시 읽는다.
 */
export function useCorrectionCandidates(feedbackId: string, enabled: boolean) {
  return useQuery({
    queryKey: correctionCandidatesQueryKey(feedbackId),
    queryFn: ({ signal }) => getCorrectionCandidates(feedbackId, signal),
    enabled,
  });
}
