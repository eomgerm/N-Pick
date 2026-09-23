'use client';

import { Check, X } from 'lucide-react';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useState } from 'react';

// 일회성 작업 성공 안내 공통 토스트 (S15P21A501-303). 화면 어디서 성공하든 토스트는 하나만
// 뜬다 — showSuccess 를 부르면 이전 안내를 최신으로 교체하고 일정 시간 뒤 자동 소멸한다.
// 여러 폼이 각자 토스트를 그리면 같은 좌표에 겹쳐 읽을 수 없으므로 Provider 로 단일화한다.
// 실패·재시도 안내는 이 토스트로 옮기지 않는다 — 각 작업 영역에 그대로 유지한다.
export const SUCCESS_TOAST_DURATION_MS = 5_000;

interface SuccessToastContextValue {
  /** 성공 안내를 띄운다. 빈 문자열은 무시한다. 같은 문구를 다시 불러도 재표시된다. */
  showSuccess: (message: string) => void;
}

const noop: SuccessToastContextValue = { showSuccess: () => {} };
const SuccessToastContext = createContext<SuccessToastContextValue | null>(null);

// Provider 밖(단위 렌더·SSR 조각)에서 불려도 던지지 않고 no-op 을 준다 — 성공 피드백은
// 화면 부수 효과라, 없다고 렌더가 깨지면 안 된다. 실제 앱에서는 layout 에 Provider 가 있다.
export function useSuccessToast(): SuccessToastContextValue {
  return useContext(SuccessToastContext) ?? noop;
}

export function SuccessToastProvider({ children }: { children: ReactNode }) {
  // key 는 호출마다 증가시켜, 같은 문구라도 라이브 영역 내용을 remount 해 다시 announce 시킨다.
  const [toast, setToast] = useState<{ message: string; key: number }>({ message: '', key: 0 });

  const showSuccess = useCallback((message: string) => {
    if (!message) return;
    setToast((prev) => ({ message, key: prev.key + 1 }));
  }, []);

  useEffect(() => {
    if (!toast.message) return;
    const timeoutId = window.setTimeout(
      () => setToast((prev) => ({ ...prev, message: '' })),
      SUCCESS_TOAST_DURATION_MS,
    );
    return () => window.clearTimeout(timeoutId);
  }, [toast.key, toast.message]);

  return (
    <SuccessToastContext.Provider value={{ showSuccess }}>
      {children}
      {/* 라이브 영역은 항상 마운트해 둔다 — 요소를 내용과 함께 붙였다 떼면 스크린리더가 변화를
          announce 하지 못한다. 안내 문구만 토글한다.
          하단 중앙에 둔다 — SearchErrorToast 등 오류 알림이 상단(top-5)을 쓰므로, 성공·오류가
          5초 안에 동시에 떠도 겹치지 않고 각각 읽고 닫을 수 있다 (S15P21A501-303). */}
      <div
        aria-atomic="true"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-4 bottom-6 z-50 mx-auto flex max-w-xl justify-center"
        role="status"
      >
        {toast.message ? (
          <div
            className="pointer-events-auto flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50/90 py-2.5 pr-2 pl-4 text-sm text-emerald-950 shadow-xl backdrop-blur"
            key={toast.key}
          >
            <Check aria-hidden="true" className="size-4 shrink-0 text-emerald-600" />
            <span>{toast.message}</span>
            <button
              aria-label="알림 닫기"
              className="flex size-7 shrink-0 items-center justify-center rounded-full text-emerald-900 hover:bg-emerald-100 focus-visible:outline-2 focus-visible:outline-offset-2"
              onClick={() => setToast((prev) => ({ ...prev, message: '' }))}
              type="button"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </div>
        ) : null}
      </div>
    </SuccessToastContext.Provider>
  );
}
