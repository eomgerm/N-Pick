'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { logout } from '@/lib/auth/api';
import { announceSessionChange, leaveSession } from '@/lib/auth/browser';

interface SessionControlsProps {
  className?: string;
  isDisabled?: boolean;
  /** 사진 배경 위의 밝은 헤더에서는 계정 정보를 유리 알약으로 감싸 대비를 확보합니다. */
  tone?: 'light' | 'brand';
}

export function SessionControls({
  className = '',
  isDisabled = false,
  tone = 'brand',
}: SessionControlsProps) {
  const member = useMember();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      announceSessionChange('logout');
      await leaveSession(client, 'logout');
    },
  });

  const isLight = tone === 'light';

  return (
    <div className={`flex flex-col items-end gap-2 text-sm ${className}`}>
      <div
        className={`flex min-w-0 flex-wrap items-center justify-end ${
          isLight
            ? 'gap-2 rounded-full border border-white/60 bg-white/70 py-1.5 pr-1.5 pl-4 shadow-[0_10px_28px_rgb(10_18_32/12%)] backdrop-blur-xl'
            : 'gap-3'
        }`}
      >
        <span className="flex min-w-0 flex-wrap justify-end gap-x-1">
          <span className="max-w-48 break-all">{member.loginId}</span>
          <span className="whitespace-nowrap">
            · {member.role === 'REVIEWER' ? '검수자' : '편집기자'}
          </span>
        </span>
        <button
          className={`shrink-0 border border-current focus-visible:outline-2 disabled:opacity-50 ${
            isLight
              ? 'rounded-full px-3.5 py-1 font-semibold focus-visible:outline-offset-2'
              : 'rounded-lg px-3 py-2 focus-visible:outline-offset-4'
          }`}
          disabled={isDisabled || mutation.isPending}
          onClick={() => {
            if (!isDisabled) mutation.mutate();
          }}
          type="button"
        >
          {mutation.isPending ? '로그아웃 중…' : '로그아웃'}
        </button>
      </div>
      {mutation.isError && (
        <div className="w-full max-w-lg">
          <ApiErrorNotice error={mutation.error} />
        </div>
      )}
    </div>
  );
}
