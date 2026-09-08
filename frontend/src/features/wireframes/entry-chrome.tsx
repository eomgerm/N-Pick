import Link from 'next/link';
import type { ReactNode } from 'react';
import { routes } from '@/lib/routes';

import styles from '@/features/wireframes/entry.module.css';

interface EntryHeaderProps {
  label: string;
  actions?: ReactNode;
}

export function EntryHeader({ label, actions }: EntryHeaderProps) {
  return (
    <header className={styles.header}>
      <Link aria-label="N-Pick 홈" className={styles.brand} href={routes.landing}>
        <span aria-hidden="true" className={styles.brandMark}>
          N
        </span>
        N-Pick
      </Link>
      {actions ?? <span className={styles.headerLabel}>{label}</span>}
    </header>
  );
}

export function EntryFooter() {
  return (
    <footer className={styles.footer}>
      <span>© N-Pick</span>
    </footer>
  );
}
