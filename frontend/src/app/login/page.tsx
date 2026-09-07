import { notFound, redirect } from 'next/navigation';

interface LoginPageProps {
  searchParams: Promise<{ role?: string | string[] }>;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const { role = 'editor' } = await searchParams;
  if (role !== 'editor' && role !== 'reviewer') notFound();
  redirect(`/login/shinhan?role=${role}`);
}
