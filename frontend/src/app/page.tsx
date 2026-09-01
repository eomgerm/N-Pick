import { AppShell } from '@/components/app-shell';
import { SearchPlaceholder } from '@/features/search/search-placeholder';
import { env } from '@/lib/env';

export default function Home() {
  return (
    <AppShell>
      <section className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="rounded-3xl border border-slate-200 bg-white p-8 shadow-sm sm:p-10">
          <p className="text-sm font-semibold tracking-[0.18em] text-blue-600 uppercase">NewsCut</p>
          <h1 className="mt-4 text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">
            프론트엔드 초기 설정이 완료되었습니다.
          </h1>
          <p className="mt-4 max-w-2xl text-base leading-7 text-slate-600">
            App Router, TypeScript, Tailwind CSS와 코드 품질 도구를 사용할 준비가 되었습니다.
          </p>
        </div>

        <aside className="rounded-3xl bg-slate-950 p-8 text-white shadow-xl shadow-slate-300/40">
          <p className="text-xs font-semibold tracking-[0.16em] text-blue-300 uppercase">
            API base host
          </p>
          <p className="mt-3 font-mono text-lg font-semibold break-all">{env.apiBaseUrl.host}</p>
          <p className="mt-6 text-sm text-slate-400">App mode: {env.appMode}</p>
        </aside>
      </section>

      <SearchPlaceholder />
    </AppShell>
  );
}
