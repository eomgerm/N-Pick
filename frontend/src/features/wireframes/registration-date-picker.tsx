'use client';

import { CalendarDays } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { isCalendarDate, MIN_SELECTABLE_DATE } from '@/features/wireframes/date-range';
import { DateRangeCalendar } from '@/features/wireframes/date-range-calendar';
import styles from '@/features/wireframes/registration-date-picker.module.css';
import calendarStyles from '@/features/wireframes/shinhan-search.module.css';
import formStyles from '@/features/wireframes/video-registration.module.css';

interface RegistrationDatePickerProps {
  id: string;
  label: string;
  value: string;
  minDate?: string;
  maxDate?: string;
  error?: string;
  isDisabled: boolean;
  onChange: (date: string) => void;
}

/** 검색 달력과 같은 월·연도 탐색을 사용하는 등록용 단일 날짜 입력입니다. */
export function RegistrationDatePicker({
  id,
  label,
  value,
  minDate = MIN_SELECTABLE_DATE,
  maxDate,
  error,
  isDisabled,
  onChange,
}: RegistrationDatePickerProps) {
  const [isOpen, setIsOpen] = useState(false);
  const fieldRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const popupRef = useRef<HTMLDivElement>(null);
  const isVisible = isOpen && !isDisabled && Boolean(maxDate);

  useLayoutEffect(() => {
    if (!isVisible || !inputRef.current || !popupRef.current) return;
    const popup = popupRef.current;
    function positionPopup() {
      const anchor = inputRef.current?.getBoundingClientRect();
      if (!anchor) return;
      if (anchor.bottom < 0 || anchor.top > window.innerHeight) {
        setIsOpen(false);
        return;
      }
      const below = anchor.bottom + 8;
      const above = anchor.top - popup.offsetHeight - 8;
      popup.style.left = `${Math.max(12, Math.min(anchor.left, window.innerWidth - popup.offsetWidth - 12))}px`;
      popup.style.top = `${Math.max(12, Math.min(below + popup.offsetHeight <= window.innerHeight - 12 ? below : above, window.innerHeight - popup.offsetHeight - 12))}px`;
    }
    positionPopup();
    popup
      .querySelector<HTMLButtonElement>('[data-date][tabindex="0"]')
      ?.focus({ preventScroll: true });
    window.addEventListener('scroll', positionPopup, true);
    window.addEventListener('resize', positionPopup);
    return () => {
      window.removeEventListener('scroll', positionPopup, true);
      window.removeEventListener('resize', positionPopup);
    };
  }, [isVisible]);

  useEffect(() => {
    if (!isVisible) return;
    const closeOutside = (event: PointerEvent) => {
      if (
        fieldRef.current?.contains(event.target as Node) ||
        popupRef.current?.contains(event.target as Node)
      )
        return;
      setIsOpen(false);
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
    };
  }, [isVisible]);

  function openPicker() {
    // 키보드 포커스의 부드러운 스크롤이 끝나기 전에도 입력 위치를 확정합니다.
    inputRef.current?.scrollIntoView({ block: 'nearest', behavior: 'instant' });
    setIsOpen(true);
  }

  function closePicker() {
    setIsOpen(false);
    inputRef.current?.focus({ preventScroll: true });
  }

  return (
    <div
      className={formStyles.dateField}
      ref={fieldRef}
      onBlur={(event) => {
        if (
          !event.relatedTarget ||
          event.currentTarget.contains(event.relatedTarget) ||
          popupRef.current?.contains(event.relatedTarget)
        )
          return;
        setIsOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && isVisible) {
          event.stopPropagation();
          closePicker();
        }
      }}
    >
      <label htmlFor={id}>
        {label} <small>선택</small>
      </label>
      <div className={styles.inputGroup}>
        <input
          aria-describedby={error ? `${id}-error` : undefined}
          aria-invalid={Boolean(error)}
          autoComplete="off"
          disabled={isDisabled}
          id={id}
          max={maxDate}
          min={minDate}
          maxLength={10}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' && maxDate) {
              event.preventDefault();
              openPicker();
            }
          }}
          placeholder="YYYY-MM-DD"
          ref={inputRef}
          type="text"
          value={value}
        />
        <button
          aria-label={`${label} 달력 열기`}
          aria-controls={`${id}-calendar`}
          aria-expanded={isVisible}
          aria-haspopup="dialog"
          className={styles.calendarButton}
          disabled={isDisabled || !maxDate}
          onClick={() => (isVisible ? closePicker() : openPicker())}
          type="button"
        >
          <CalendarDays aria-hidden="true" />
        </button>
      </div>
      {error && (
        <p className={formStyles.error} id={`${id}-error`} role="alert">
          {error}
        </p>
      )}
      {isVisible &&
        maxDate &&
        createPortal(
          <div
            aria-label={`${label} 선택`}
            className={`${calendarStyles.calendarPopover} ${styles.popover}`}
            id={`${id}-calendar`}
            ref={popupRef}
            role="dialog"
          >
            <DateRangeCalendar
              endpoint="from"
              initialDate={isCalendarDate(value) ? value : maxDate}
              isSingleDate
              label={label}
              minDate={minDate}
              maxDate={maxDate}
              onSelect={(date) => {
                onChange(date);
                closePicker();
              }}
              range={{ from: isCalendarDate(value) ? value : '', to: '' }}
            />
            <div className={calendarStyles.popoverFooter}>
              <button
                onClick={() => {
                  onChange('');
                  closePicker();
                }}
                type="button"
              >
                날짜 지우기
              </button>
              <button className={calendarStyles.primaryButton} onClick={closePicker} type="button">
                닫기
              </button>
            </div>
          </div>,
          document.body,
        )}
    </div>
  );
}
