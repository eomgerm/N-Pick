'use client';

import { CalendarDays, X } from 'lucide-react';
import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

import {
  createRecentYearRange,
  type DateRange,
  emptyDateRange,
  formatDateRange,
  type RecentYearPreset,
  validateDateRange,
} from '@/features/wireframes/date-range';
import { DateRangeCalendar } from '@/features/wireframes/date-range-calendar';
import styles from '@/features/wireframes/shinhan-search.module.css';
import { seoulToday } from '@/lib/seoul-date';

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

/**
 * 달력 버튼에 붙는 기간 선택 드롭다운입니다.
 * 화면을 덮는 모달 대신 트리거 아래에 작은 패널을 띄우고,
 * 바깥 클릭·Esc·포커스 이탈로 닫습니다. 선택 값은 "적용"에서 반영하고,
 * 초기화는 한 번의 클릭으로 즉시 반영합니다.
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
  const [today, setToday] = useState('');
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
    // 사용자 이벤트에서 읽어 hydration을 피하고, 다시 열 때 날짜를 갱신합니다.
    const currentToday = seoulToday();
    setToday(currentToday);
    navigationTrigger?.onOpen();
    const trigger = triggerRef.current?.getBoundingClientRect();
    // 아래 공간이 모자라면 위로 펼칩니다.
    const spaceBelow = trigger ? window.innerHeight - trigger.bottom : Number.POSITIVE_INFINITY;
    setPlacement(spaceBelow < 380 && trigger && trigger.top > spaceBelow ? 'above' : 'below');
    setDraft(value);
    setError(validateDateRange(value, currentToday));
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
                  onChange(emptyDateRange);
                  handleClose();
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
          <div aria-label="빠른 기간 선택" className={styles.datePresets} role="group">
            {([1, 2, 3] as const).map((years: RecentYearPreset) => {
              const preset = createRecentYearRange(years, today);
              const isSelected = draft.from === preset.from && draft.to === preset.to;
              return (
                <button
                  aria-pressed={isSelected}
                  data-selected={isSelected}
                  key={years}
                  onClick={() => {
                    setDraft(preset);
                    setError('');
                  }}
                  type="button"
                >
                  최근 {years}년
                </button>
              );
            })}
          </div>
          <div className={styles.calendarColumns}>
            {(['from', 'to'] as const).map((endpoint) => (
              <DateRangeCalendar
                endpoint={endpoint}
                initialDate={draft[endpoint] || value[endpoint] || value.from || today}
                maxDate={today}
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
                const message = validateDateRange(draft, seoulToday());
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
