'use client';

import { useCallback, useEffect, useRef } from 'react';

import type { InquiryStatus } from '@/features/wireframes/inquiry-state';

// 검수 취소를 확정하면 취소 버튼이 사라지므로(성공, 또는 이미 풀려 409 뒤 다시 불러온 경우)
// 키보드 포커스가 body 로 떨어진다. 다시 불러온 상세가 OPEN 으로 그려진 뒤 「검수 시작」으로 옮긴다
// (S15P21A501-289). 검수 중으로 남는 실패는 취소 컨트롤이 자기 버튼으로 돌려준다.
export function useClaimReleaseFocus(status: InquiryStatus | undefined) {
  const claimButtonRef = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);

  useEffect(() => {
    if (!pending.current || status === 'reviewing') return;
    pending.current = false;
    if (status === 'open') claimButtonRef.current?.focus();
  }, [status]);

  const markReleaseConfirmed = useCallback(() => {
    pending.current = true;
  }, []);

  return { claimButtonRef, markReleaseConfirmed };
}
