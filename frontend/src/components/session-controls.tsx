'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { logout } from '@/lib/auth/api';
import { announceSessionChange, leaveSession } from '@/lib/auth/browser';
import { routes } from '@/lib/routes';

interface SessionControlsProps {
  showReviewLink?: boolean;
  className?: string;
}

export function SessionControls({ showReviewLink = true, className = '' }: SessionControlsProps) {
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
      {showReviewLink && member.role === 'REVIEWER' && (
        <Link href={routes.review} prefetch={false}>
          검수
        </Link>
      )}
      <span className="max-w-48 truncate" title={member.loginId}>
        {member.loginId} · {member.role === 'REVIEWER' ? '검수자' : '편집기자'}
      </span>
      <button
        className="rounded-lg border border-current px-3 py-2 disabled:opacity-50"
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
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
