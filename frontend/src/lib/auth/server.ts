import 'server-only';

import { cache } from 'react';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { fetchJson } from '@/lib/api/client';
import {
  canAccessPath,
  isSessionExpired,
  loginPath,
  parseMember,
  postLoginPath,
} from '@/lib/auth/member';
import { internalApiBaseUrl } from '@/lib/server-env';

export const currentMember = cache(async () => {
  const session = (await cookies()).get('JSESSIONID')?.value;
  if (!session || !/^[a-z\d._~-]+$/i.test(session)) return null;
  try {
    return parseMember(
      await fetchJson(
        '/auth/me',
        {
          headers: { Cookie: `JSESSIONID=${session}` },
          cache: 'no-store',
          signal: AbortSignal.timeout(10_000),
        },
        internalApiBaseUrl(),
      ),
    );
  } catch (error) {
    if (isSessionExpired(error)) return null;
    // An unavailable backend is NOT an anonymous user or an empty success.
    throw error;
  }
});

export async function requireMember(location: string) {
  const member = await currentMember();
  if (!member) {
    const refresh = (await cookies()).get('NPICK_REFRESH')?.value;
    if (refresh && /^[a-f0-9]{64}$/.test(refresh)) {
      redirect(`/session/renew?${new URLSearchParams({ returnTo: location })}`);
    }
    redirect(loginPath(location));
  }
  if (!canAccessPath(member.role, location.split('?')[0])) {
    redirect(`${postLoginPath(member.role)}?notice=forbidden`);
  }
  return member;
}
