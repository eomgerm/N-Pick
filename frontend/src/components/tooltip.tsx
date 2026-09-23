'use client';

import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import styles from '@/components/tooltip.module.css';

interface TooltipProps {
  content: string;
  children: (descriptionId: string | undefined) => ReactNode;
  placement?: 'right' | 'bottom';
  onClickOnly?: boolean;
  isDisabled?: boolean;
}

export function Tooltip({
  content,
  children,
  placement = 'right',
  onClickOnly = false,
  isDisabled = false,
}: TooltipProps) {
  const id = useId();
  const [isOpen, setIsOpen] = useState(false);
  const anchorRef = useRef<HTMLSpanElement>(null);
  const tooltipRef = useRef<HTMLSpanElement>(null);
  const isVisible = isOpen && !isDisabled;

  useLayoutEffect(() => {
    if (!isVisible || !anchorRef.current || !tooltipRef.current) return;
    const anchor = anchorRef.current.getBoundingClientRect();
    const tooltip = tooltipRef.current;
    const left =
      placement === 'right'
        ? anchor.right + 10
        : anchor.left + anchor.width / 2 - tooltip.offsetWidth / 2;
    const top =
      placement === 'right'
        ? anchor.top + (anchor.height - tooltip.offsetHeight) / 2
        : anchor.bottom + 8;
    tooltip.style.left = `${Math.max(12, Math.min(left, window.innerWidth - tooltip.offsetWidth - 12))}px`;
    tooltip.style.top = `${Math.max(12, Math.min(top, window.innerHeight - tooltip.offsetHeight - 12))}px`;
  }, [isVisible, placement, content]);

  useEffect(() => {
    if (!isVisible) return;
    const close = () => setIsOpen(false);
    window.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('scroll', close, true);
      window.removeEventListener('resize', close);
    };
  }, [isVisible]);

  return (
    <span
      className={styles.anchor}
      data-click-only={onClickOnly}
      onBlur={() => setIsOpen(false)}
      onClick={() => setIsOpen(onClickOnly)}
      onFocus={() => {
        if (!onClickOnly) setIsOpen(true);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && isVisible) {
          event.stopPropagation();
          setIsOpen(false);
        }
      }}
      onMouseEnter={() => {
        if (!onClickOnly) setIsOpen(true);
      }}
      onMouseLeave={() => setIsOpen(false)}
      ref={anchorRef}
    >
      {children(isVisible ? id : undefined)}
      {isVisible &&
        createPortal(
          <span className={styles.tooltip} id={id} ref={tooltipRef} role="tooltip">
            {content}
          </span>,
          document.body,
        )}
    </span>
  );
}
