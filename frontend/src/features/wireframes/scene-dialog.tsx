'use client';

import { type ReactNode, useEffect, useRef } from 'react';

import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';

interface SceneDialogProps {
  children: ReactNode;
  className: string;
  describedBy?: string;
  labelledBy: string;
  theme: WireframeTheme;
  isLocked?: boolean;
  onClose: () => void;
}

export function SceneDialog({
  children,
  className,
  describedBy,
  labelledBy,
  theme,
  isLocked = false,
  onClose,
}: SceneDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    dialog?.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      dialog?.close();
      document.body.style.overflow = previousOverflow;
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) {
        previousFocus.focus({ preventScroll: true });
      }
    };
  }, []);

  return (
    <dialog
      aria-describedby={describedBy}
      aria-labelledby={labelledBy}
      className={`${styles.dialogTheme} ${styles.dialog} ${className}`}
      data-theme={theme}
      onCancel={(event) => {
        event.preventDefault();
        if (!isLocked) onClose();
      }}
      onMouseDown={(event) => {
        if (isLocked || event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (
          event.clientX < bounds.left ||
          event.clientX > bounds.right ||
          event.clientY < bounds.top ||
          event.clientY > bounds.bottom
        ) {
          event.preventDefault();
          onClose();
        }
      }}
      ref={dialogRef}
    >
      {children}
    </dialog>
  );
}
