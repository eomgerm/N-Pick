import { ApiClientError } from '@/lib/api/error';
import { routes } from '@/lib/routes';

export type MemberRole = 'EDITOR' | 'REVIEWER';

const maxSignedInt64 = '9223372036854775807';

function isPositiveInt64(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return false;
  return (
    value.length < maxSignedInt64.length ||
    (value.length === maxSignedInt64.length && value <= maxSignedInt64)
  );
}

export interface Member {
  memberId: string;
  loginId: string;
  role: MemberRole;
}

export function parseMember(value: unknown): Member {
  if (value && typeof value === 'object') {
    const member = value as Partial<Member>;
    if (
      isPositiveInt64(member.memberId) &&
      typeof member.loginId === 'string' &&
      member.loginId.trim() &&
      (member.role === 'EDITOR' || member.role === 'REVIEWER')
    ) {
      return { memberId: member.memberId, loginId: member.loginId, role: member.role };
    }
  }
  throw new ApiClientError('invalid-response', 200);
}

export function isSessionExpired(error: unknown): boolean {
  return error instanceof ApiClientError && error.status === 401 && error.code === 'COMM_401';
}

export function isInvalidCredentials(error: unknown): boolean {
  return error instanceof ApiClientError && error.status === 401 && error.code === 'MEMBER_401_001';
}

export function canAccessPath(role: MemberRole, pathname: string): boolean {
  return (
    pathname === routes.search ||
    pathname === routes.searchResults ||
    (role === 'REVIEWER' && pathname === routes.review)
  );
}

export function safeReturnTo(value: unknown): string | undefined {
  if (
    typeof value !== 'string' ||
    value.length > 8192 ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    /[\\\u0000-\u0020\u007f]/.test(value)
  )
    return undefined;
  const url = new URL(value, 'https://npick.invalid');
  if (
    url.origin !== 'https://npick.invalid' ||
    url.hash ||
    !canAccessPath('REVIEWER', url.pathname)
  ) {
    return undefined;
  }
  return `${url.pathname}${url.search}`;
}

export function postLoginPath(role: MemberRole, returnTo?: string): string {
  const destination = safeReturnTo(returnTo);
  if (destination && canAccessPath(role, new URL(destination, 'https://npick.invalid').pathname)) {
    return destination;
  }
  return role === 'REVIEWER' ? routes.review : routes.search;
}

export function loginPath(returnTo?: string, reason?: 'expired' | 'logout'): string {
  const params = new URLSearchParams();
  const destination = safeReturnTo(returnTo);
  if (destination) {
    params.set('returnTo', destination);
    if (destination.split('?')[0] === routes.review) params.set('role', 'reviewer');
  }
  if (reason) params.set('reason', reason);
  return `${routes.login}${params.size ? `?${params}` : ''}`;
}

export function pageLocation(
  path: string,
  params: Record<string, string | string[] | undefined>,
): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) value.forEach((item) => query.append(key, item));
    else if (value !== undefined) query.set(key, value);
  }
  return `${path}${query.size ? `?${query}` : ''}`;
}
