'use client';

import Link from 'next/link';
import { routes } from '@/lib/routes';

export default function ErrorPage({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <main className="mx-auto max-w-xl space-y-5 p-8">
      <h1 className="text-xl font-semibold">화면을 불러오지 못했습니다.</h1>
      <p role="alert">
        서버 연결 또는 로그인 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.
      </p>
      <button className="rounded-lg border px-4 py-2" onClick={reset} type="button">
        다시 시도
      </button>
      <Link className="ml-4 underline" href={routes.login}>
        로그인 화면
      </Link>
    </main>
  );
}
