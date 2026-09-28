'use client';

import { Film, Inbox, LayoutDashboard, Plus } from 'lucide-react';
import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import type { ReactNode } from 'react';

import { AppShell } from '@/components/app-shell';
import {
  getReviewTabUrl,
  getReviewUrl,
  selectReviewView,
  type ReviewTab,
} from '@/features/wireframes/reviewer-board-state';
import styles from '@/features/wireframes/reviewer.module.css';
import sidebarStyles from '@/features/wireframes/search-history.module.css';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';

interface ReviewerLayoutProps {
  children: ReactNode;
  theme: WireframeTheme;
  headerContent?: ReactNode;
  isInteractionLocked?: boolean;
  onTabChange?: (tab: ReviewTab) => void;
  onRegistrationOpen?: () => void;
}

export function ReviewerLayout({
  children,
  theme,
  headerContent,
  isInteractionLocked = false,
  onTabChange,
  onRegistrationOpen,
}: ReviewerLayoutProps) {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const view = selectReviewView(searchParams);
  const currentParams = searchParams.toString();
  const items = [
    {
      label: '개요',
      icon: LayoutDashboard,
      isCurrent: view === 'overview',
      href: getReviewTabUrl(pathname, currentParams, 'overview'),
      onSelect: onTabChange ? () => onTabChange('overview') : undefined,
    },
    {
      label: '문의',
      icon: Inbox,
      isCurrent: view === 'inquiries',
      href: getReviewTabUrl(pathname, currentParams, 'inquiries'),
      onSelect: onTabChange ? () => onTabChange('inquiries') : undefined,
    },
    {
      label: '처리',
      icon: Film,
      isCurrent: view === 'processing',
      href: getReviewTabUrl(pathname, currentParams, 'processing'),
      onSelect: onTabChange ? () => onTabChange('processing') : undefined,
    },
    {
      label: '영상 등록',
      icon: Plus,
      isCurrent: view === 'upload',
      href: getReviewUrl(pathname, currentParams, {
        view: 'upload',
        tab: null,
        clip: null,
        inquiry: null,
        progressPage: null,
      }),
      onSelect: onRegistrationOpen,
    },
  ];

  return (
    <AppShell
      backdropTone="dark"
      className={`${styles.shell} ${view === 'processing' ? styles.processingShell : ''}`}
      data-theme={theme}
      headerTone="light"
      headerContent={headerContent}
      isInteractionLocked={isInteractionLocked}
    >
      <aside aria-label="검수 도구" className={sidebarStyles.navDock}>
        <div className={sidebarStyles.navSurface}>
          <nav aria-label="검수 화면" className={sidebarStyles.navActions}>
            {items.map(({ label, icon: Icon, isCurrent, href, onSelect }) => (
              <Link
                key={label}
                aria-current={isCurrent ? 'page' : undefined}
                aria-disabled={isInteractionLocked}
                aria-label={label}
                className={sidebarStyles.navAction}
                data-active={isCurrent}
                href={href}
                onClick={(event) => {
                  if (isInteractionLocked) event.preventDefault();
                }}
                onNavigate={(event) => {
                  if (isInteractionLocked || onSelect) event.preventDefault();
                  if (!isInteractionLocked) onSelect?.();
                }}
                prefetch={false}
                scroll={false}
                title={label}
              >
                <Icon aria-hidden="true" />
              </Link>
            ))}
          </nav>
        </div>
      </aside>
      {children}
    </AppShell>
  );
}
