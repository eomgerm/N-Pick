'use client';

import { CalendarDays, Check, ChevronDown, ChevronLeft } from 'lucide-react';
import { type ReactNode, useId, useLayoutEffect, useRef, useState } from 'react';

import {
  createRecentYearRange,
  type DateBasis,
  dateBasisLabels,
  type DateRange,
  emptyDateRange,
  formatDateRange,
  type RecentYearPreset,
  type SearchDateRanges,
  validateDateRange,
} from '@/features/wireframes/date-range';
import { DateRangeCalendar } from '@/features/wireframes/date-range-calendar';
import styles from '@/features/wireframes/shinhan-search.module.css';
import { seoulToday } from '@/lib/seoul-date';

interface DateRangePickerProps {
  ranges: SearchDateRanges;
  triggerDescriptionId?: string;
  isDisabled?: boolean;
  isOpen: boolean;
  isCompact?: boolean;
  navigationTrigger?: {
    className: string;
    icon: ReactNode;
  };
  onOpenChange: (isOpen: boolean) => void;
  onChange: (value: SearchDateRanges) => void;
}

/**
 * 달력 버튼에 붙는 기간 선택 드롭다운입니다.
 * 화면을 덮는 모달 대신 트리거 아래에 작은 패널을 띄우고,
 * 열림 상태와 바깥 클릭·포커스 이탈은 부모 탐색 영역에서 관리합니다.
 * 선택 값은 "적용"에서 반영하고,
 * 초기화는 패널을 연 채 한 번의 클릭으로 즉시 반영합니다.
 */
export function DateRangePicker({
  ranges,
  triggerDescriptionId,
  isDisabled,
  isOpen,
  isCompact = false,
  navigationTrigger,
  onOpenChange,
  onChange,
}: DateRangePickerProps) {
  const id = useId();
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [today, setToday] = useState('');
  const [basis, setBasis] = useState<DateBasis>(
    ranges.filming.from && !ranges.broadcast.from ? 'filming' : 'broadcast',
  );
  const [drafts, setDrafts] = useState(ranges);
  const [isBasisOpen, setIsBasisOpen] = useState(false);
  const basisRef = useRef<HTMLDivElement>(null);
  const draft = drafts[basis];
  const value = ranges[basis];
  const appliedBases = (['broadcast', 'filming'] as const).filter(
    (key) => ranges[key].from && ranges[key].to,
  );
  const rangeSummary =
    appliedBases
      .map((key) => `${dateBasisLabels[key]} ${formatDateRange(ranges[key])}`)
      .join(' · ') || '전체 기간';
  const [error, setError] = useState('');
  const [placement, setPlacement] = useState<'above' | 'below'>('below');

  function setDraft(next: DateRange | ((current: DateRange) => DateRange)) {
    setDrafts((current) => ({
      ...current,
      [basis]: typeof next === 'function' ? next(current[basis]) : next,
    }));
  }

  function openBasisOptions() {
    setIsBasisOpen(true);
    requestAnimationFrame(() =>
      basisRef.current?.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus(),
    );
  }

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
    const trigger = triggerRef.current?.getBoundingClientRect();
    // 아래 공간이 모자라면 위로 펼칩니다.
    const spaceBelow = trigger ? window.innerHeight - trigger.bottom : Number.POSITIVE_INFINITY;
    setPlacement(spaceBelow < 380 && trigger && trigger.top > spaceBelow ? 'above' : 'below');
    setDrafts(ranges);
    setIsBasisOpen(false);
    setError(validateDateRange(value, currentToday));
    onOpenChange(true);
  }

  function handleClose() {
    onOpenChange(false);
    triggerRef.current?.focus();
  }

  return (
    <div
      className={styles.dateField}
      data-compact={isCompact}
      data-navigation={Boolean(navigationTrigger)}
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || !isOpen) return;
        event.stopPropagation();
        handleClose();
      }}
      ref={containerRef}
    >
      {!navigationTrigger && <span>기간 설정</span>}
      <button
        aria-controls={`${id}-panel`}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={`기간 설정: ${rangeSummary}`}
        aria-describedby={triggerDescriptionId}
        className={navigationTrigger?.className ?? styles.rangeTrigger}
        data-active={isOpen}
        data-applied={appliedBases.length > 0}
        disabled={isDisabled}
        onClick={() => (isOpen ? handleClose() : handleOpen())}
        ref={triggerRef}
        type="button"
      >
        {navigationTrigger ? navigationTrigger.icon : <CalendarDays aria-hidden="true" />}
        {navigationTrigger ? (
          <span className={styles.navigationLabel}>
            <span>기간 설정</span>
            {appliedBases.length > 0 ? (
              <small aria-hidden="true" className={styles.navigationRange}>
                {appliedBases.map((key) => (
                  <span key={key}>{dateBasisLabels[key]}</span>
                ))}
              </small>
            ) : null}
          </span>
        ) : !isCompact ? (
          <span>{rangeSummary}</span>
        ) : null}
      </button>
      {isOpen ? (
        <div
          aria-label="기간 설정"
          className={styles.calendarPopover}
          data-placement={placement}
          id={`${id}-panel`}
          role="dialog"
        >
          <div className={styles.popoverHeader}>
            <div
              className={styles.basisControl}
              onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget)) setIsBasisOpen(false);
              }}
              onKeyDown={(event) => {
                if (event.key === 'Escape' && isBasisOpen) {
                  event.stopPropagation();
                  setIsBasisOpen(false);
                  basisRef.current?.querySelector<HTMLButtonElement>('[aria-haspopup]')?.focus();
                }
              }}
              ref={basisRef}
            >
              <button
                aria-label="기준 선택"
                aria-haspopup="listbox"
                aria-expanded={isBasisOpen}
                aria-controls={`${id}-basis`}
                className={styles.basisTrigger}
                onClick={() => (isBasisOpen ? setIsBasisOpen(false) : openBasisOptions())}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                    event.preventDefault();
                    openBasisOptions();
                  }
                }}
                type="button"
              >
                {dateBasisLabels[basis]}
                <ChevronDown aria-hidden="true" />
              </button>
              {isBasisOpen && (
                <div
                  aria-label="기준 선택"
                  className={styles.basisOptions}
                  id={`${id}-basis`}
                  role="listbox"
                >
                  {(['broadcast', 'filming'] as const).map((key, index) => (
                    <button
                      aria-selected={basis === key}
                      key={key}
                      onClick={() => {
                        setBasis(key);
                        setError(validateDateRange(drafts[key], today));
                        setIsBasisOpen(false);
                        basisRef.current
                          ?.querySelector<HTMLButtonElement>('[aria-haspopup]')
                          ?.focus();
                      }}
                      onKeyDown={(event) => {
                        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
                        event.preventDefault();
                        const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - index;
                        basisRef.current
                          ?.querySelectorAll<HTMLButtonElement>('[role="option"]')
                          [next]?.focus();
                      }}
                      role="option"
                      tabIndex={basis === key ? 0 : -1}
                      type="button"
                    >
                      {dateBasisLabels[key]}
                      {basis === key && <Check aria-hidden="true" />}
                    </button>
                  ))}
                </div>
              )}
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
            <div className={styles.popoverActions}>
              <button
                className={styles.resetButton}
                onClick={() => {
                  setDraft(emptyDateRange);
                  setError('');
                  onChange({ ...ranges, [basis]: emptyDateRange });
                }}
                type="button"
              >
                초기화
              </button>
              <button
                aria-label="기간 설정 닫기"
                className={styles.popoverClose}
                onClick={handleClose}
                type="button"
              >
                <ChevronLeft aria-hidden="true" />
              </button>
            </div>
          </div>
          <div className={styles.calendarColumns}>
            {(['from', 'to'] as const).map((endpoint) => (
              <DateRangeCalendar
                endpoint={endpoint}
                initialDate={draft[endpoint] || value[endpoint] || value.from || today}
                maxDate={today}
                key={`${basis}-${endpoint}`}
                onSelect={(date) => {
                  setDraft((current) => {
                    const next = { ...current, [endpoint]: date };
                    return next.from && next.to && next.from > next.to
                      ? { from: next.to, to: next.from }
                      : next;
                  });
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
            <button
              className={styles.primaryButton}
              onClick={() => {
                const currentToday = seoulToday();
                for (const key of ['broadcast', 'filming'] as const) {
                  const message = validateDateRange(drafts[key], currentToday);
                  if (message) {
                    setBasis(key);
                    setError(message);
                    return;
                  }
                }
                handleClose();
                onChange(drafts);
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
