'use client';

import { CalendarDays, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { type KeyboardEvent, useId, useRef, useState } from 'react';

import {
  type DateRange,
  emptyDateRange,
  formatDateRange,
  isCalendarDate,
  selectRangeDate,
  validateDateRange,
} from '@/features/wireframes/date-range';
import styles from '@/features/wireframes/shinhan-search.module.css';

interface DateRangePickerProps {
  label: string;
  value: DateRange;
  isDisabled?: boolean;
  onChange: (value: DateRange) => void;
}

const weekdays = ['일', '월', '화', '수', '목', '금', '토'];
const iso = (date: Date) => date.toISOString().slice(0, 10);
const monthStart = (date: string) => `${date.slice(0, 7)}-01`;

function shiftMonth(month: string, offset: number) {
  const date = new Date(`${month}T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() + offset);
  return iso(date);
}

export function DateRangePicker({ label, value, isDisabled, onChange }: DateRangePickerProps) {
  const id = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [draft, setDraft] = useState(value);
  const [month, setMonth] = useState('2026-01-01');
  const [focusDate, setFocusDate] = useState('');
  const [error, setError] = useState('');

  function handleOpen() {
    const today = new Date();
    const localToday = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    const start = value.from || localToday;
    setDraft(value);
    setMonth(monthStart(start));
    setFocusDate(start);
    setError('');
    dialogRef.current?.showModal();
  }

  function handleClose() {
    dialogRef.current?.close();
    triggerRef.current?.focus();
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
    if (next < month || next >= shiftMonth(month, 2)) setMonth(monthStart(next));
    setFocusDate(next);
    requestAnimationFrame(() =>
      dialogRef.current?.querySelector<HTMLButtonElement>(`[data-date="${next}"]`)?.focus(),
    );
  }

  return (
    <div className={styles.dateField}>
      <span>{label}</span>
      <button
        aria-label={`${label} 기간 선택: ${formatDateRange(value)}`}
        aria-haspopup="dialog"
        className={styles.rangeTrigger}
        disabled={isDisabled}
        onClick={handleOpen}
        ref={triggerRef}
        type="button"
      >
        <CalendarDays aria-hidden="true" />
        <span>{formatDateRange(value)}</span>
      </button>
      <dialog
        aria-labelledby={`${id}-title`}
        className={styles.calendarDialog}
        onCancel={(event) => {
          event.preventDefault();
          handleClose();
        }}
        ref={dialogRef}
      >
        <header className={styles.calendarHeader}>
          <div>
            <p>기간 선택</p>
            <h2 id={`${id}-title`}>{label}로 장면 찾기</h2>
          </div>
          <button aria-label={`${label} 기간 선택 닫기`} onClick={handleClose} type="button">
            <X aria-hidden="true" />
          </button>
        </header>
        <p className={styles.calendarHint}>
          시작일과 종료일을 선택해 주세요. 선택한 두 날짜를 모두 포함해요.
        </p>
        <div className={styles.dateInputs}>
          {(['from', 'to'] as const).map((field) => (
            <label key={field}>
              {field === 'from' ? '시작일' : '종료일'}
              <input
                aria-describedby={error ? `${id}-error` : undefined}
                aria-invalid={Boolean(error)}
                onChange={(event) => {
                  const next = { ...draft, [field]: event.target.value };
                  setDraft(next);
                  setError('');
                  if (field === 'from' && isCalendarDate(next.from)) {
                    setMonth(monthStart(next.from));
                    setFocusDate(next.from);
                  }
                }}
                type="date"
                value={draft[field]}
              />
            </label>
          ))}
        </div>
        <div className={styles.monthNavigation}>
          <button
            aria-label="이전 달"
            onClick={() => {
              const next = shiftMonth(month, -1);
              setMonth(next);
              setFocusDate(next);
            }}
            type="button"
          >
            <ChevronLeft aria-hidden="true" />
          </button>
          <span>날짜를 눌러 기간을 선택하세요</span>
          <button
            aria-label="다음 달"
            onClick={() => {
              const next = shiftMonth(month, 1);
              setMonth(next);
              setFocusDate(next);
            }}
            type="button"
          >
            <ChevronRight aria-hidden="true" />
          </button>
        </div>
        <div className={styles.months}>
          {[month, shiftMonth(month, 1)].map((visibleMonth) => {
            const first = new Date(`${visibleMonth}T00:00:00Z`);
            const year = first.getUTCFullYear();
            const monthNumber = first.getUTCMonth() + 1;
            const dayCount = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();
            return (
              <section aria-label={`${year}년 ${monthNumber}월`} key={visibleMonth}>
                <h3>
                  {year}년 {monthNumber}월
                </h3>
                <div className={styles.days}>
                  {weekdays.map((day) => (
                    <span aria-hidden="true" className={styles.weekday} key={day}>
                      {day}
                    </span>
                  ))}
                  {Array.from({ length: first.getUTCDay() }, (_, index) => (
                    <span aria-hidden="true" key={`blank-${index}`} />
                  ))}
                  {Array.from({ length: dayCount }, (_, index) => {
                    const day = `${visibleMonth.slice(0, 8)}${String(index + 1).padStart(2, '0')}`;
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
                        data-within={isWithin}
                        data-date={day}
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
              </section>
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
        <footer className={styles.calendarFooter}>
          <button
            onClick={() => {
              setDraft(emptyDateRange);
              setError('');
            }}
            type="button"
          >
            전체 기간으로 초기화
          </button>
          <div>
            <button onClick={handleClose} type="button">
              취소
            </button>
            <button
              className={styles.primaryButton}
              onClick={() => {
                const message = validateDateRange(draft);
                setError(message);
                if (!message) {
                  handleClose();
                  onChange(draft);
                }
              }}
              type="button"
            >
              적용
            </button>
          </div>
        </footer>
      </dialog>
    </div>
  );
}
