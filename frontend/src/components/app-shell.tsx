'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';

import { AppBackdrop } from '@/components/app-backdrop';
import { AppBrand } from '@/components/app-brand';
import { MountainBackdrop } from '@/components/mountain-backdrop';
import { useMember } from '@/components/session-boundary';
import { SessionControls } from '@/components/session-controls';
import styles from '@/components/app-shell.module.css';
import { routes } from '@/lib/routes';

interface AppShellProps {
  children: ReactNode;
  className?: string;
  'data-theme'?: string;
  headerTone?: 'light' | 'brand';
  isInteractionLocked?: boolean;
  backdropTone?: 'clear' | 'muted' | 'dark';
  isBackdropUnveiled?: boolean;
  hasHeader?: boolean;
  headerContent?: ReactNode;
}

export function AppShell({
  children,
  className,
  'data-theme': theme,
  headerTone = 'brand',
  isInteractionLocked = false,
  backdropTone = 'clear',
  isBackdropUnveiled = false,
  hasHeader = true,
  headerContent,
}: AppShellProps) {
  const member = useMember();
  const pathname = usePathname();
  const isReview = pathname === routes.review;
  const isSearchEntry = pathname === routes.search;
  const isSearch = isSearchEntry || pathname === routes.searchResults;
  const hasFloatingHeader = isSearch || isReview;
  const navigation = [
    { href: routes.search, label: '장면 검색', isCurrent: !isReview },
    ...(member.role === 'REVIEWER'
      ? [{ href: routes.review, label: '검수', isCurrent: isReview }]
      : []),
  ];

  const navigationLinks = (
    <nav
      aria-label="주요 메뉴"
      className={
        hasFloatingHeader
          ? 'grid auto-cols-fr grid-flow-col gap-1'
          : 'order-last col-span-2 flex items-stretch gap-7 lg:order-none lg:col-span-1'
      }
    >
      {navigation.map(({ href, label, isCurrent }) => (
        <Link
          key={href}
          aria-current={isCurrent ? 'page' : undefined}
          aria-disabled={isInteractionLocked}
          className={
            hasFloatingHeader
              ? 'min-w-0 rounded-xl px-3 py-3 text-center text-sm font-semibold whitespace-nowrap hover:bg-white/60 focus-visible:outline-2 aria-[current=page]:bg-white/70'
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
  );

  return (
    <div
      className={[className, hasFloatingHeader && styles.floatingLayout].filter(Boolean).join(' ')}
      data-theme={theme}
    >
      {isSearchEntry ? (
        <MountainBackdrop />
      ) : (
        <AppBackdrop tone={backdropTone} unveiled={isBackdropUnveiled} />
      )}
      {hasHeader && (
        <header
          className={
            hasFloatingHeader
              ? styles.searchHeader
              : `grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-6 gap-y-2 px-5 py-3 sm:px-8 lg:grid-cols-[220px_minmax(0,1fr)_auto] ${
                  headerTone === 'light'
                    ? 'mx-auto min-h-25 w-full max-w-7xl text-(--text)'
                    : 'sticky top-0 z-20 min-h-17 bg-(--secondary) text-white'
                }`
          }
          data-has-content={Boolean(headerContent)}
        >
          {!hasFloatingHeader && (
            <AppBrand
              ariaDisabled={isInteractionLocked}
              className="focus-visible:outline-2 focus-visible:outline-offset-4"
              onClick={(event) => {
                if (isInteractionLocked) event.preventDefault();
              }}
            />
          )}
          {!hasFloatingHeader && navigationLinks}
          <SessionControls
            className={
              hasFloatingHeader ? styles.searchAccount : 'min-w-0 justify-self-end lg:max-w-md'
            }
            isDisabled={isInteractionLocked}
            navigation={hasFloatingHeader ? navigationLinks : undefined}
            tone={headerTone}
          />
          {hasFloatingHeader && headerContent && (
            <div className={styles.searchHeaderContent}>{headerContent}</div>
          )}
        </header>
      )}
      {hasHeader && hasFloatingHeader && (
        <div
          aria-hidden="true"
          className={styles.searchHeaderSpace}
          data-has-search={isSearch && Boolean(headerContent)}
        />
      )}
      {children}
    </div>
  );
}
