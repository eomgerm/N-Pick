import { fetchJson } from '@/lib/api/client';
import { ApiClientError } from '@/lib/api/error';
import { isSessionExpired, parseMember } from '@/lib/auth/member';

export interface LoginInput {
  loginId: string;
  password: string;
}

export async function login(input: LoginInput) {
  const member = parseMember(
    await fetchJson('/auth/login', {
      method: 'POST',
      body: input,
      signal: AbortSignal.timeout(15_000),
    }),
  );
  try {
    const confirmed = await getMember();
    if (member.memberId !== confirmed.memberId || member.role !== confirmed.role) {
      throw new ApiClientError('invalid-response', 200);
    }
    return confirmed;
  } catch (error) {
    if (isSessionExpired(error)) {
      throw new ApiClientError('api', 0, {
        code: 'CLIENT_SESSION_COOKIE_UNAVAILABLE',
        message:
          '로그인 쿠키를 유지하지 못했습니다. 쿠키 허용 여부와 서버의 접속 주소·보안 설정을 확인해 주세요.',
      });
    }
    throw error;
  }
}

export async function logout() {
  await fetchJson<void>('/auth/logout', { method: 'POST', signal: AbortSignal.timeout(15_000) });
}

export async function getMember(signal?: AbortSignal) {
  return parseMember(
    await fetchJson('/auth/me', {
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
        : AbortSignal.timeout(15_000),
    }),
  );
}

export const memberQueryOptions = {
  queryKey: ['auth', 'me'] as const,
  queryFn: ({ signal }: { signal: AbortSignal }) => getMember(signal),
  staleTime: 0,
  retry: false as const,
};
