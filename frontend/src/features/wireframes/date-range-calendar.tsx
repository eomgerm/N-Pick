'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { type KeyboardEvent, useRef, useState } from 'react';

import type { DateRange } from '@/features/wireframes/date-range';
import styles from '@/features/wireframes/shinhan-search.module.css';

interface DateRangeCalendarProps {
  endpoint: keyof DateRange;
  initialDate: string;
  range: DateRange;
  onSelect: (date: string) => void;
}

const weekdays = ['일', '월', '화', '수', '목', '금', '토'];
const monthStart = (date: string) => `${date.slice(0, 7)}-01`;
const yearMonth = (year: number, month: number) =>
  `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-01`;
type CalendarView = 'days' | 'months' | 'years';

interface CalendarState {
  initialDate: string;
  month: string;
  focusDate: string;
  view: CalendarView;
}

const createCalendarState = (initialDate: string): CalendarState => ({
  initialDate,
  month: monthStart(initialDate),
  focusDate: initialDate,
  view: 'days',
});

/** 시작일과 종료일이 각각 탐색 위치와 보기 단계를 갖는 달력입니다. */
export function DateRangeCalendar({
  endpoint,
  initialDate,
  range,
  onSelect,
}: DateRangeCalendarProps) {
  const [calendarState, setCalendarState] = useState(() => createCalendarState(initialDate));
  const currentState =
    calendarState.initialDate === initialDate ? calendarState : createCalendarState(initialDate);
  const { month, focusDate, view } = currentState;
  const calendarRef = useRef<HTMLElement>(null);

  const label = endpoint === 'from' ? '시작일' : '종료일';
  const first = new Date(`${month}T00:00:00Z`);
  const year = first.getUTCFullYear();
  const monthNumber = first.getUTCMonth() + 1;
  const last = new Date(first);
  last.setUTCMonth(last.getUTCMonth() + 1);
  last.setUTCDate(0);
  const decade = Math.floor(year / 10) * 10;
  const firstYear = Math.max(1, decade);
  const lastYear = Math.min(9999, decade + 10);
  const period =
    view === 'days'
      ? `${year}년 ${monthNumber}월`
      : view === 'months'
        ? `${year}년`
        : `${firstYear} - ${lastYear}`;
  const shiftLabel = view === 'days' ? '달' : view === 'months' ? '연도' : '연도 범위';

  function focusDay(date: string) {
    requestAnimationFrame(() =>
      calendarRef.current?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)?.focus(),
    );
  }

  function updateCalendarState(updater: (state: CalendarState) => CalendarState) {
    setCalendarState((state) =>
      updater(state.initialDate === initialDate ? state : createCalendarState(initialDate)),
    );
  }

  function showMonth(next: string, shouldFocus = false) {
    const selected = range[endpoint];
    const nextFocus = selected && monthStart(selected) === next ? selected : next;
    updateCalendarState((state) => ({
      ...state,
      month: next,
      focusDate: nextFocus,
      view: 'days',
    }));
    if (shouldFocus) focusDay(nextFocus);
  }

  function shiftPeriod(offset: number) {
    if (view === 'days') {
      const next = new Date(first);
      next.setUTCMonth(next.getUTCMonth() + offset);
      showMonth(next.toISOString().slice(0, 10));
    } else {
      const nextYear = Math.max(1, Math.min(9999, year + offset * (view === 'years' ? 10 : 1)));
      updateCalendarState((state) => ({
        ...state,
        month: yearMonth(nextYear, monthNumber),
      }));
    }
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
    if (date.getUTCFullYear() < 1 || date.getUTCFullYear() > 9999) return;
    const next = date.toISOString().slice(0, 10);
    updateCalendarState((state) => ({
      ...state,
      month: monthStart(next),
      focusDate: next,
    }));
    focusDay(next);
  }

  return (
    <section
      aria-label={`${label} 선택`}
      className={styles.endpointCalendar}
      data-endpoint={endpoint}
      ref={calendarRef}
    >
      <div className={styles.endpointHeading}>
        <h3>{label}</h3>
      </div>
      <div className={styles.monthNavigation}>
        <button
          aria-label={`${label} 이전 ${shiftLabel}`}
          disabled={
            view === 'days' ? month === '0001-01-01' : view === 'years' ? decade === 0 : year === 1
          }
          onClick={() => shiftPeriod(-1)}
          type="button"
        >
          <ChevronLeft aria-hidden="true" />
        </button>
        <button
          aria-disabled={view === 'years'}
          aria-label={`${label} ${period}${view === 'days' ? ', 월 선택' : view === 'months' ? ', 연도 선택' : ', 연도 범위'}`}
          className={styles.periodTitle}
          onClick={() => {
            if (view !== 'years') {
              updateCalendarState((state) => ({
                ...state,
                view: view === 'days' ? 'months' : 'years',
              }));
            }
          }}
          type="button"
        >
          <span aria-live="polite">{period}</span>
        </button>
        <button
          aria-label={`${label} 다음 ${shiftLabel}`}
          disabled={
            view === 'days'
              ? month === '9999-12-01'
              : view === 'years'
                ? decade === 9990
                : year === 9999
          }
          onClick={() => shiftPeriod(1)}
          type="button"
        >
          <ChevronRight aria-hidden="true" />
        </button>
      </div>
      {view === 'days' ? (
        <div aria-label={period} className={styles.days} role="group">
          {weekdays.map((day) => (
            <span aria-hidden="true" className={styles.weekday} key={day}>
              {day}
            </span>
          ))}
          {Array.from({ length: first.getUTCDay() }, (_, index) => (
            <span aria-hidden="true" key={`blank-${index}`} />
          ))}
          {Array.from({ length: last.getUTCDate() }, (_, index) => {
            const day = `${month.slice(0, 8)}${String(index + 1).padStart(2, '0')}`;
            const isBoundary = day === range.from || day === range.to;
            const isWithin = Boolean(
              range.from && range.to && day >= range.from && day <= range.to,
            );
            return (
              <button
                aria-label={`${year}년 ${monthNumber}월 ${index + 1}일${day === range.from ? ', 시작일' : ''}${day === range.to ? ', 종료일' : ''}`}
                aria-pressed={day === range[endpoint]}
                className={styles.day}
                data-boundary={isBoundary}
                data-date={day}
                data-within={isWithin}
                key={day}
                onClick={() => {
                  updateCalendarState((state) => ({ ...state, focusDate: day }));
                  onSelect(day);
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
      ) : (
        <div
          aria-label={`${label} ${view === 'months' ? '월' : '연도'} 목록`}
          className={styles.periodGrid}
          role="group"
        >
          {Array.from({ length: view === 'months' ? 12 : lastYear - firstYear + 1 }, (_, index) => {
            const number = view === 'months' ? index + 1 : firstYear + index;
            return (
              <button
                aria-pressed={number === (view === 'months' ? monthNumber : year)}
                data-month={view === 'months' ? number : undefined}
                key={number}
                onClick={() => {
                  if (view === 'months') showMonth(yearMonth(year, number), true);
                  else {
                    updateCalendarState((state) => ({
                      ...state,
                      month: yearMonth(number, monthNumber),
                      view: 'months',
                    }));
                    requestAnimationFrame(() =>
                      calendarRef.current
                        ?.querySelector<HTMLButtonElement>(`[data-month="${monthNumber}"]`)
                        ?.focus(),
                    );
                  }
                }}
                type="button"
              >
                {number}
                {view === 'months' ? '월' : '년'}
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}
