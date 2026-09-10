'use client';

import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react';

import {
  type DateRange,
  emptyDateRange,
  formatDateRange,
  selectRangeDate,
  validateDateRange,
} from '@/features/wireframes/date-range';
import styles from '@/features/wireframes/shinhan-search.module.css';

interface DateRangePickerProps {
  label: string;
  value: DateRange;
  isDisabled?: boolean;
  isCompact?: boolean;
  onChange: (value: DateRange) => void;
}

const weekdays = ['일', '월', '화', '수', '목', '금', '토'];
const iso = (date: Date) => date.toISOString().slice(0, 10);
const monthStart = (date: string) => `${date.slice(0, 7)}-01`;

function localToday() {
  const today = new Date();
  const month = String(today.getMonth() + 1).padStart(2, '0');
  const day = String(today.getDate()).padStart(2, '0');
  return `${today.getFullYear()}-${month}-${day}`;
}

function shiftMonth(month: string, offset: number) {
  const date = new Date(`${month}T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() + offset);
  return iso(date);
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
  onChange,
}: DateRangePickerProps) {
  const id = useId();
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const [month, setMonth] = useState(() => monthStart(localToday()));
  const [focusDate, setFocusDate] = useState('');
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

  function focusDay(day: string) {
    requestAnimationFrame(() =>
      containerRef.current?.querySelector<HTMLButtonElement>(`[data-date="${day}"]`)?.focus(),
    );
  }

  function handleOpen() {
    const start = value.from || localToday();
    const trigger = triggerRef.current?.getBoundingClientRect();
    // 아래 공간이 모자라면 위로 펼칩니다.
    const spaceBelow = trigger ? window.innerHeight - trigger.bottom : Number.POSITIVE_INFINITY;
    setPlacement(spaceBelow < 380 && trigger && trigger.top > spaceBelow ? 'above' : 'below');
    setDraft(value);
    setMonth(monthStart(start));
    setFocusDate(start);
    setError('');
    setIsOpen(true);
    focusDay(start);
  }

  function handleClose() {
    setIsOpen(false);
    triggerRef.current?.focus();
  }

  function handleMonthShift(offset: number) {
    const next = shiftMonth(month, offset);
    setMonth(next);
    setFocusDate(next);
  }

  function handleDayKey(event: KeyboardEvent<HTMLButtonElement>, day: string) {
    const date = new Date(`${day}T00:00:00Z`);
    const offsets: Record<string, number> = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -7,
      ArrowDown: 7,
      Home: -date.getUTCDay(),
      End: 6 - date.getUTCDay(),
    };
    if (!(event.key in offsets)) return;
    event.preventDefault();
    date.setUTCDate(date.getUTCDate() + offsets[event.key]);
    const next = iso(date);
    if (next < month || next >= shiftMonth(month, 1)) setMonth(monthStart(next));
    setFocusDate(next);
    focusDay(next);
  }

  const first = new Date(`${month}T00:00:00Z`);
  const year = first.getUTCFullYear();
  const monthNumber = first.getUTCMonth() + 1;
  const dayCount = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();

  return (
    <div
      className={styles.dateField}
      data-compact={isCompact}
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
      <span>{label}</span>
      <button
        aria-controls={`${id}-panel`}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={`${label} 기간 선택: ${formatDateRange(value)}`}
        className={styles.rangeTrigger}
        disabled={isDisabled}
        onClick={() => (isOpen ? handleClose() : handleOpen())}
        ref={triggerRef}
        title={isCompact ? `${label} 기간 선택: ${formatDateRange(value)}` : undefined}
        type="button"
      >
        <CalendarDays aria-hidden="true" />
        {!isCompact ? <span>{formatDateRange(value)}</span> : null}
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
          </div>
          <div className={styles.monthNavigation}>
            <button aria-label="이전 달" onClick={() => handleMonthShift(-1)} type="button">
              <ChevronLeft aria-hidden="true" />
            </button>
            <span aria-live="polite">
              {year}년 {monthNumber}월
            </span>
            <button aria-label="다음 달" onClick={() => handleMonthShift(1)} type="button">
              <ChevronRight aria-hidden="true" />
            </button>
          </div>
          <div aria-label={`${year}년 ${monthNumber}월`} className={styles.days} role="group">
            {weekdays.map((day) => (
              <span aria-hidden="true" className={styles.weekday} key={day}>
                {day}
              </span>
            ))}
            {Array.from({ length: first.getUTCDay() }, (_, index) => (
              <span aria-hidden="true" key={`blank-${index}`} />
            ))}
            {Array.from({ length: dayCount }, (_, index) => {
              const day = `${month.slice(0, 8)}${String(index + 1).padStart(2, '0')}`;
              const isBoundary = day === draft.from || day === draft.to;
              const isWithin = Boolean(
                draft.from && draft.to && day >= draft.from && day <= draft.to,
              );
              return (
                <button
                  aria-label={`${year}년 ${monthNumber}월 ${index + 1}일${day === draft.from ? ', 시작일' : ''}${day === draft.to ? ', 종료일' : ''}`}
                  aria-pressed={isBoundary || isWithin}
                  className={styles.day}
                  data-boundary={isBoundary}
                  data-date={day}
                  data-within={isWithin}
                  key={day}
                  onClick={() => {
                    setDraft(selectRangeDate(draft, day));
                    setFocusDate(day);
                    setError('');
                  }}
                  onKeyDown={(event) => handleDayKey(event, day)}
                  tabIndex={day === focusDate ? 0 : -1}
                  type="button"
                >
                  {index + 1}
                </button>
              );
            })}
          </div>
          <p aria-live="polite" className={styles.rangeSummary}>
            {draft.from && !draft.to
              ? `${draft.from.replaceAll('-', '.')}부터 · 종료일을 선택해 주세요`
              : formatDateRange(draft)}
          </p>
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
