'use client';

import { focusManager, onlineManager } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { useSyncExternalStore } from 'react';

import {
  processingRefreshState,
  type ProcessingRefreshStateInput,
} from '@/features/wireframes/clip-processing-view';

const subscribeOnline = (onChange: () => void) => onlineManager.subscribe(onChange);
const subscribeFocus = (onChange: () => void) => focusManager.subscribe(onChange);
const getOnline = () => onlineManager.isOnline();
const getFocused = () => focusManager.isFocused();
const getServerSnapshot = () => true;

export function useProcessingRefreshState(
  input: Omit<ProcessingRefreshStateInput, 'isOnline' | 'isFocused'>,
) {
  const isOnline = useSyncExternalStore(subscribeOnline, getOnline, getServerSnapshot);
  const isFocused = useSyncExternalStore(subscribeFocus, getFocused, getServerSnapshot);
  return processingRefreshState({ ...input, isOnline, isFocused });
}

interface ProcessingRefreshStatusProps {
  state: ReturnType<typeof processingRefreshState>;
  dataUpdatedAt: number;
}

const refreshLabels = {
  automatic: '5초마다 처리 상태를 자동으로 확인합니다.',
  refreshing: '처리 상태 확인 중…',
  paused: '자동 확인이 일시 중지되었습니다. 연결과 화면이 복구되면 다시 확인합니다.',
  error: '조회 오류로 자동 확인을 중단했습니다. 새로고침으로 다시 확인해 주세요.',
  manual: '자동 확인이 종료되었습니다. 새로고침으로 다시 확인할 수 있습니다.',
};

export function ProcessingRefreshStatus({ state, dataUpdatedAt }: ProcessingRefreshStatusProps) {
  return (
    // 주기적인 요청·시각 변경을 live region으로 반복 낭독하지 않는다.
    <div
      className="my-3 grid gap-1 text-xs leading-relaxed text-(--muted)"
      role="group"
      aria-label="영상 상태 갱신 안내"
    >
      <div className="flex items-start gap-2">
        <RefreshCw
          aria-hidden="true"
          className={`mt-0.5 size-3.5 shrink-0 ${state.mode === 'refreshing' ? 'motion-safe:animate-spin' : ''}`}
        />
        <span>{refreshLabels[state.mode]}</span>
      </div>
      <div>
        마지막 확인{' '}
        {dataUpdatedAt > 0 ? (
          <time dateTime={new Date(dataUpdatedAt).toISOString()}>
            {new Date(dataUpdatedAt).toLocaleString('ko-KR', { hour12: false })}
          </time>
        ) : (
          '기록 없음'
        )}
      </div>
    </div>
  );
}
