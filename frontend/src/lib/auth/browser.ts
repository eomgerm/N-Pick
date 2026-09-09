import type { QueryClient } from '@tanstack/react-query';
import { loginPath } from '@/lib/auth/member';

export const sessionChannel = 'npick-session';
// Message identity only, never an authentication credential or persisted value.
export const sessionMessageSource = crypto.randomUUID();

export function otherTabSessionEvent(data: unknown): 'login' | 'logout' | undefined {
  if (!data || typeof data !== 'object') return undefined;
  const message = data as { source?: unknown; event?: unknown };
  if (typeof message.source !== 'string' || message.source === sessionMessageSource)
    return undefined;
  return message.event === 'login' || message.event === 'logout' ? message.event : undefined;
}

// Only N-Pick's member-scoped drafts are owned here. Never storage.clear().
export function clearMemberDrafts(storage: Storage, keepMemberId?: string): void {
  const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index));
  for (const key of keys) {
    if (
      key &&
      /^npick:\d+:/.test(key) &&
      (keepMemberId === undefined || !key.startsWith(`npick:${keepMemberId}:`))
    ) {
      storage.removeItem(key);
    }
  }
}

export function cleanBrowserDrafts(keepMemberId?: string): void {
  try {
    clearMemberDrafts(window.sessionStorage, keepMemberId);
  } catch {
    // Storage can be disabled; it is never the source of authentication.
  }
}

export function announceSessionChange(event: 'login' | 'logout'): void {
  if (typeof BroadcastChannel === 'undefined') return;
  const channel = new BroadcastChannel(sessionChannel);
  channel.postMessage({ event, source: sessionMessageSource });
  channel.close();
}

export async function discardQueries(client: QueryClient): Promise<void> {
  await client.cancelQueries();
  client.clear();
}

let isLeavingSession = false;

export async function leaveSession(
  client: QueryClient,
  reason: 'expired' | 'logout',
): Promise<void> {
  if (isLeavingSession) return;
  isLeavingSession = true;
  if (reason === 'logout') cleanBrowserDrafts();
  await discardQueries(client);
  window.location.replace(
    loginPath(
      reason === 'expired' ? window.location.pathname + window.location.search : undefined,
      reason,
    ),
  );
}
