import { SessionRenewal } from '@/components/session-renewal';
import { safeReturnTo } from '@/lib/auth/member';
import { routes } from '@/lib/routes';

export default async function SessionRenewalPage({
  searchParams,
}: {
  searchParams: Promise<{ returnTo?: string | string[] }>;
}) {
  const { returnTo } = await searchParams;
  return <SessionRenewal returnTo={safeReturnTo(returnTo) ?? routes.search} />;
}
