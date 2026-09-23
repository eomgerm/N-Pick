'use client';

import { Check, X } from 'lucide-react';
import { useEffect, useState } from 'react';

// 일회성 작업 성공 안내 공통 토스트 (S15P21A501-303). SearchErrorToast 와 같은 자동 소멸
// 구조를 쓰되, 성공은 완료 사실만 알리는 일회성 피드백이라 일정 시간 뒤 화면에서 사라진다.
// 실패·재시도 안내는 이 토스트로 옮기지 않는다 — 각 작업 영역에 그대로 유지한다.
export const SUCCESS_TOAST_DURATION_MS = 5_000;

interface SuccessToastProps {
  /** 표시할 성공 문구. 빈 문자열이면 토스트를 띄우지 않는다(라이브 영역 요소는 유지). */
  message: string;
  /**
   * 같은 문구가 연속으로 떠야 할 때 재표시를 강제하는 토큰. 값이 바뀔 때마다 토스트가 다시
   * 뜬다. message 가 작업마다 달라지면(예: id 포함) 생략해도 된다.
   */
  token?: string | number;
}

export function SuccessToast({ message, token }: SuccessToastProps) {
  // message(또는 token)가 바뀔 때마다 새 성공 안내로 교체하며 다시 띄운다(누적하지 않음).
  const changeKey = token ?? message;
  const [shownKey, setShownKey] = useState(changeKey);
  const [visible, setVisible] = useState(Boolean(message));

  // prop 변경 시 상태를 렌더 중에 조정하는 React 공식 패턴 — effect 안에서 동기 setState 를
  // 하면 연쇄 렌더를 부르므로, 재표시(누적 방지)는 여기서 처리한다.
  if (changeKey !== shownKey) {
    setShownKey(changeKey);
    setVisible(Boolean(message));
  }

  // 자동 소멸만 effect+타이머로 처리한다(SearchErrorToast 와 같은 구조).
  useEffect(() => {
    if (!visible || !message) return;
    const timeoutId = window.setTimeout(() => setVisible(false), SUCCESS_TOAST_DURATION_MS);
    return () => window.clearTimeout(timeoutId);
  }, [visible, message, token]);

  const shown = visible ? message : '';

  // 라이브 영역은 항상 마운트해 둔다 — 요소를 내용과 함께 붙였다 떼면 스크린리더가 변화를
  // announce 하지 못한다. 안내 문구만 토글한다.
  return (
    <div
      aria-atomic="true"
      aria-live="polite"
      className="pointer-events-none fixed inset-x-4 top-5 z-50 mx-auto flex max-w-xl justify-center"
      role="status"
    >
      {shown ? (
        <div className="pointer-events-auto flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50/90 py-2.5 pr-2 pl-4 text-sm text-emerald-950 shadow-xl backdrop-blur">
          <Check aria-hidden="true" className="size-4 shrink-0 text-emerald-600" />
          <span>{shown}</span>
          <button
            aria-label="알림 닫기"
            className="flex size-7 shrink-0 items-center justify-center rounded-full text-emerald-900 hover:bg-emerald-100 focus-visible:outline-2 focus-visible:outline-offset-2"
            onClick={() => setVisible(false)}
            type="button"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </div>
      ) : null}
    </div>
  );
}
