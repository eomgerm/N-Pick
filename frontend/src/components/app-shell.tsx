import type { ReactNode } from 'react';

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6 py-10 sm:px-10 lg:py-16">
      <header className="mb-10 flex items-center justify-between border-b border-slate-200 pb-5">
        <span className="text-xl font-black tracking-tight text-slate-950">N-Pick</span>
        <span className="rounded-full bg-blue-100 px-3 py-1 text-xs font-semibold text-blue-700">
          Initial setup
        </span>
      </header>

      <div className="flex flex-1 flex-col gap-6">{children}</div>
    </main>
  );
}
