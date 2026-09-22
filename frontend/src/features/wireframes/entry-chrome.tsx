import type { ReactNode } from 'react';

import { AppBrand } from '@/components/app-brand';

import styles from '@/features/wireframes/entry.module.css';

interface EntryHeaderProps {
  label?: string;
  actions?: ReactNode;
}

export function EntryHeader({ label, actions }: EntryHeaderProps) {
  return (
    <header className={styles.header}>
      <AppBrand />
      {actions ?? (label ? <span className={styles.headerLabel}>{label}</span> : null)}
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
