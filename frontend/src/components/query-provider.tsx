'use client';

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, useEffect, useState } from 'react';
import {
  discardQueries,
  leaveSession,
  otherTabSessionEvent,
  sessionChannel,
} from '@/lib/auth/browser';
import { isSessionExpired } from '@/lib/auth/member';

export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(() => {
    const onError = (error: unknown) => {
      if (isSessionExpired(error)) void leaveSession(queryClient, 'expired');
    };
    const queryClient = new QueryClient({
      queryCache: new QueryCache({ onError }),
      mutationCache: new MutationCache({ onError }),
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    return queryClient;
  });

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const channel = new BroadcastChannel(sessionChannel);
    channel.onmessage = (event: MessageEvent<unknown>) => {
      const change = otherTabSessionEvent(event.data);
      if (change === 'logout') void leaveSession(client, 'logout');
      else if (change === 'login') {
        void discardQueries(client).then(() => window.location.reload());
      }
    };
    return () => channel.close();
  }, [client]);

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
