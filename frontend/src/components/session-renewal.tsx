'use client';

import { useEffect, useState } from 'react';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { renewBrowserSession } from '@/lib/api/client';
import { getMember } from '@/lib/auth/api';
import { isSessionExpired, loginPath, postLoginPath } from '@/lib/auth/member';

interface SessionRenewalProps {
  returnTo: string;
}

export function SessionRenewal({ returnTo }: SessionRenewalProps) {
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    let active = true;
    void renewBrowserSession()
      .then(() => getMember())
      .then((member) => {
        if (active) window.location.replace(postLoginPath(member.role, returnTo));
      })
      .catch((failure: unknown) => {
        if (!active) return;
        if (isSessionExpired(failure)) window.location.replace(loginPath(returnTo, 'expired'));
        else setError(failure);
      });
    return () => {
      active = false;
    };
  }, [attempt, returnTo]);

  return (
    <main className="mx-auto max-w-xl p-8" aria-busy={!error}>
      {error ? (
        <>
          <ApiErrorNotice error={error} />
          <button
            className="mt-4 rounded-lg border px-4 py-2"
            type="button"
            onClick={() => {
              setError(undefined);
              setAttempt((value) => value + 1);
            }}
          >
            다시 시도
          </button>
        </>
      ) : (
        <p role="status">로그인 상태를 갱신하고 있어요.</p>
      )}
    </main>
  );
}
