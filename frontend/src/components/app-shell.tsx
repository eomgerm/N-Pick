'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';

import { AppBackdrop } from '@/components/app-backdrop';
import { AppBrand } from '@/components/app-brand';
import { useMember } from '@/components/session-boundary';
import { SessionControls } from '@/components/session-controls';
import { routes } from '@/lib/routes';

interface AppShellProps {
  children: ReactNode;
  className?: string;
  'data-theme'?: string;
  headerTone?: 'light' | 'brand';
  isInteractionLocked?: boolean;
  backdropTone?: 'clear' | 'muted';
  isBackdropUnveiled?: boolean;
}

export function AppShell({
  children,
  className,
  'data-theme': theme,
  headerTone = 'brand',
  isInteractionLocked = false,
  backdropTone = 'clear',
  isBackdropUnveiled = false,
}: AppShellProps) {
  const member = useMember();
  const pathname = usePathname();
  const isReview = pathname === routes.review;
  // 사진 배경 위에 놓이는 밝은 헤더는 글자만으로 대비를 확보할 수 없어
  // 메뉴와 세션 정보를 반투명 유리 알약 안에 담습니다.
  const isLightHeader = headerTone === 'light';
  const navigation = [
    { href: routes.search, label: '장면 검색', isCurrent: !isReview },
    ...(member.role === 'REVIEWER'
      ? [{ href: routes.review, label: '검수', isCurrent: isReview }]
      : []),
  ];

  return (
    <div className={className} data-theme={theme}>
      <AppBackdrop tone={backdropTone} unveiled={isBackdropUnveiled} />
      <header
        className={`grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-6 gap-y-2 px-5 py-3 sm:px-8 lg:grid-cols-[220px_minmax(0,1fr)_auto] ${
          headerTone === 'light'
            ? 'mx-auto min-h-25 w-full max-w-7xl text-(--text)'
            : 'sticky top-0 z-20 min-h-17 bg-(--secondary) text-white'
        }`}
      >
        <AppBrand
          ariaDisabled={isInteractionLocked}
          className="focus-visible:outline-2 focus-visible:outline-offset-4"
          onClick={(event) => {
            if (isInteractionLocked) event.preventDefault();
          }}
        />
        <nav
          aria-label="주요 메뉴"
          className={`order-last col-span-2 flex items-stretch lg:order-none lg:col-span-1 ${
            isLightHeader
              ? 'w-fit gap-1 rounded-full border border-white/60 bg-white/70 p-1 shadow-[0_10px_28px_rgb(10_18_32/12%)] backdrop-blur-xl'
              : 'gap-7'
          }`}
        >
          {navigation.map(({ href, label, isCurrent }) => (
            <Link
              key={href}
              aria-current={isCurrent ? 'page' : undefined}
              aria-disabled={isInteractionLocked}
              className={
                isLightHeader
                  ? 'inline-flex items-center rounded-full px-4 py-2 text-sm font-semibold opacity-70 hover:opacity-100 focus-visible:outline-2 focus-visible:outline-offset-2 aria-[current=page]:bg-(--text) aria-[current=page]:text-white aria-[current=page]:opacity-100'
                  : 'inline-flex items-center border-b-3 border-transparent py-2 text-sm font-semibold opacity-75 hover:opacity-100 focus-visible:outline-2 focus-visible:outline-offset-4 aria-[current=page]:border-current aria-[current=page]:opacity-100'
              }
              href={href}
              onClick={(event) => {
                if (isInteractionLocked) event.preventDefault();
              }}
              prefetch={false}
            >
              {label}
            </Link>
          ))}
        </nav>
        <SessionControls
          className="min-w-0 lg:max-w-md"
          isDisabled={isInteractionLocked}
          tone={headerTone}
        />
      </header>
      {children}
    </div>
  );
}
