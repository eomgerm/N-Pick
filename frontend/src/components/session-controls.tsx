'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { logout } from '@/lib/auth/api';
import { announceSessionChange, leaveSession } from '@/lib/auth/browser';

interface SessionControlsProps {
  className?: string;
  isDisabled?: boolean;
}

export function SessionControls({ className = '', isDisabled = false }: SessionControlsProps) {
  const member = useMember();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      announceSessionChange('logout');
      await leaveSession(client, 'logout');
    },
  });

  return (
    <div className={`flex flex-wrap items-center justify-end gap-3 text-sm ${className}`}>
      <span className="flex min-w-0 flex-wrap justify-end gap-x-1">
        <span className="max-w-48 break-all">{member.loginId}</span>
        <span className="whitespace-nowrap">
          · {member.role === 'REVIEWER' ? '검수자' : '편집기자'}
        </span>
      </span>
      <button
        className="shrink-0 rounded-lg border border-current px-3 py-2 focus-visible:outline-2 focus-visible:outline-offset-4 disabled:opacity-50"
        disabled={isDisabled || mutation.isPending}
        onClick={() => {
          if (!isDisabled) mutation.mutate();
        }}
        type="button"
      >
        {mutation.isPending ? '로그아웃 중…' : '로그아웃'}
      </button>
      {mutation.isError && (
        <div className="w-full max-w-lg">
          <ApiErrorNotice error={mutation.error} />
        </div>
      )}
    </div>
  );
}
