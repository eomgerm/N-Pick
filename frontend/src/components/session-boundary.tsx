'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useEffect } from 'react';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { memberQueryOptions } from '@/lib/auth/api';
import { cleanBrowserDrafts, discardQueries } from '@/lib/auth/browser';
import { type Member, postLoginPath } from '@/lib/auth/member';

const MemberContext = createContext<Member | null>(null);

export function useMember(): Member {
  const member = useContext(MemberContext);
  if (!member) throw new Error('useMember requires SessionBoundary.');
  return member;
}

interface SessionBoundaryProps {
  member: Member;
  children: ReactNode;
}

export function SessionBoundary({ member, children }: SessionBoundaryProps) {
  const client = useQueryClient();
  const session = useQuery({
    ...memberQueryOptions,
    initialData: member,
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
  });
  const hasChangedMember =
    session.data.memberId !== member.memberId || session.data.role !== member.role;

  useEffect(() => {
    if (hasChangedMember) {
      cleanBrowserDrafts(session.data.memberId);
      void discardQueries(client).then(() =>
        window.location.replace(
          postLoginPath(session.data.role, window.location.pathname + window.location.search),
        ),
      );
    } else {
      cleanBrowserDrafts(member.memberId);
    }
  }, [client, hasChangedMember, member.memberId, session.data.memberId, session.data.role]);

  useEffect(() => {
    const checkRestoredPage = (event: PageTransitionEvent) => {
      if (event.persisted) window.location.reload();
    };
    window.addEventListener('pageshow', checkRestoredPage);
    return () => window.removeEventListener('pageshow', checkRestoredPage);
  }, []);

  const isBlocked = session.isFetching || session.isError || hasChangedMember;
  return (
    <MemberContext value={session.data}>
      {isBlocked && (
        <main className="mx-auto max-w-xl p-8" aria-busy={session.isFetching}>
          {session.isError ? (
            <>
              <ApiErrorNotice error={session.error} />
              <button
                className="mt-4 rounded-lg border px-4 py-2"
                onClick={() => void session.refetch()}
                type="button"
              >
                로그인 상태 다시 확인
              </button>
            </>
          ) : (
            <p role="status">로그인 상태를 확인하고 있어요.</p>
          )}
        </main>
      )}
      {/* Keep local/form state mounted during a focus/reconnect check. */}
      <div hidden={isBlocked}>{children}</div>
    </MemberContext>
  );
}
