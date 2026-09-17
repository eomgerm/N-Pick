'use client';

import { CalendarDays, X } from 'lucide-react';
import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

import {
  type DateRange,
  emptyDateRange,
  formatDateRange,
  validateDateRange,
} from '@/features/wireframes/date-range';
import { DateRangeCalendar } from '@/features/wireframes/date-range-calendar';
import styles from '@/features/wireframes/shinhan-search.module.css';

interface DateRangePickerProps {
  label: string;
  value: DateRange;
  isDisabled?: boolean;
  isCompact?: boolean;
  navigationTrigger?: {
    className: string;
    icon: ReactNode;
    onOpen: () => void;
  };
  onChange: (value: DateRange) => void;
}

function localToday() {
  const today = new Date();
  const month = String(today.getMonth() + 1).padStart(2, '0');
  const day = String(today.getDate()).padStart(2, '0');
  return `${today.getFullYear()}-${month}-${day}`;
}

/**
 * 달력 버튼에 붙는 기간 선택 드롭다운입니다.
 * 화면을 덮는 모달 대신 트리거 아래에 작은 패널을 띄우고,
 * 바깥 클릭·Esc·포커스 이탈로 닫습니다. 값은 "적용"에서만 반영합니다.
 */
export function DateRangePicker({
  label,
  value,
  isDisabled,
  isCompact = false,
  navigationTrigger,
  onChange,
}: DateRangePickerProps) {
  const id = useId();
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const [error, setError] = useState('');
  const [placement, setPlacement] = useState<'above' | 'below'>('below');

  useEffect(() => {
    if (!isOpen) return;
    function handlePointerDown(event: PointerEvent) {
      if (containerRef.current?.contains(event.target as Node)) return;
      setIsOpen(false);
    }
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [isOpen]);

  useLayoutEffect(() => {
    if (isOpen) {
      containerRef.current
        ?.querySelector<HTMLButtonElement>('[data-endpoint="from"] [tabindex="0"]')
        ?.focus({ preventScroll: true });
    }
  }, [isOpen]);

  function handleOpen() {
    navigationTrigger?.onOpen();
    const trigger = triggerRef.current?.getBoundingClientRect();
    // 아래 공간이 모자라면 위로 펼칩니다.
    const spaceBelow = trigger ? window.innerHeight - trigger.bottom : Number.POSITIVE_INFINITY;
    setPlacement(spaceBelow < 380 && trigger && trigger.top > spaceBelow ? 'above' : 'below');
    setDraft(value);
    setError('');
    setIsOpen(true);
  }

  function handleClose() {
    setIsOpen(false);
    triggerRef.current?.focus();
  }

  return (
    <div
      className={styles.dateField}
      data-compact={isCompact}
      data-navigation={Boolean(navigationTrigger)}
      onBlur={(event) => {
        if (!isOpen || !event.relatedTarget) return;
        if (containerRef.current?.contains(event.relatedTarget)) return;
        setIsOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || !isOpen) return;
        event.stopPropagation();
        handleClose();
      }}
      ref={containerRef}
    >
      {!navigationTrigger && <span>{label}</span>}
      <button
        aria-controls={`${id}-panel`}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={`${label} 기간 선택: ${formatDateRange(value)}`}
        className={navigationTrigger?.className ?? styles.rangeTrigger}
        data-active={isOpen}
        data-applied={Boolean(value.from && value.to)}
        disabled={isDisabled}
        onClick={() => (isOpen ? handleClose() : handleOpen())}
        ref={triggerRef}
        title={isCompact ? `${label} 기간 선택: ${formatDateRange(value)}` : undefined}
        type="button"
      >
        {navigationTrigger ? navigationTrigger.icon : <CalendarDays aria-hidden="true" />}
        {navigationTrigger ? (
          <span className={styles.navigationLabel}>
            <span>{label}</span>
            {value.from && value.to ? (
              <small aria-hidden="true" className={styles.navigationRange}>
                <time dateTime={value.from}>{value.from.replaceAll('-', '.')}</time>
                <span>
                  – <time dateTime={value.to}>{value.to.replaceAll('-', '.')}</time>
                </span>
              </small>
            ) : null}
          </span>
        ) : !isCompact ? (
          <span>{formatDateRange(value)}</span>
        ) : null}
      </button>
      {isOpen ? (
        <div
          aria-labelledby={`${id}-title`}
          className={styles.calendarPopover}
          data-placement={placement}
          id={`${id}-panel`}
          role="dialog"
        >
          <div className={styles.popoverHeader}>
            <h2 id={`${id}-title`}>{label} 기간</h2>
            <div className={styles.popoverActions}>
              <button
                className={styles.resetButton}
                onClick={() => {
                  setDraft(emptyDateRange);
                  setError('');
                }}
                type="button"
              >
                초기화
              </button>
              <button
                aria-label={`${label} 기간 선택 닫기`}
                className={styles.popoverClose}
                onClick={handleClose}
                type="button"
              >
                <X aria-hidden="true" />
              </button>
            </div>
          </div>
          <div className={styles.calendarColumns}>
            {(['from', 'to'] as const).map((endpoint) => (
              <DateRangeCalendar
                endpoint={endpoint}
                initialDate={value[endpoint] || value.from || localToday()}
                key={endpoint}
                onSelect={(date) => {
                  setDraft((current) => ({ ...current, [endpoint]: date }));
                  setError('');
                }}
                range={draft}
              />
            ))}
          </div>
          {error ? (
            <p className={styles.fieldError} id={`${id}-error`} role="alert">
              {error}
            </p>
          ) : null}
          <div className={styles.popoverFooter}>
            <button onClick={handleClose} type="button">
              취소
            </button>
            <button
              className={styles.primaryButton}
              onClick={() => {
                const message = validateDateRange(draft);
                setError(message);
                if (message) return;
                handleClose();
                onChange(draft);
              }}
              type="button"
            >
              적용
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
