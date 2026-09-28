'use client';

import { X } from 'lucide-react';
import { useEffect, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { presentSearchError } from '@/features/wireframes/search-error-presentation';

interface SearchErrorToastProps {
  error: unknown;
}

export function SearchErrorToast({ error }: SearchErrorToastProps) {
  const [dismissedError, setDismissedError] = useState<unknown>();
  const presentation = presentSearchError(error);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => setDismissedError(error), 5_000);
    return () => window.clearTimeout(timeoutId);
  }, [error]);

  if (dismissedError === error) return null;

  return (
    <div className="fixed inset-x-4 top-5 z-50 mx-auto max-w-xl drop-shadow-xl [&>div]:pr-14">
      <ApiErrorNotice
        error={error}
        message={presentation.message}
        followUp={presentation.followUp}
      />
      <button
        aria-label="오류 알림 닫기"
        className="absolute top-2 right-2 flex size-9 items-center justify-center rounded-full text-red-950 hover:bg-red-100 focus-visible:outline-2 focus-visible:outline-offset-2"
        onClick={() => setDismissedError(error)}
        type="button"
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}
