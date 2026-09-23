'use client';

import { CalendarDays, ChevronLeft, History, MessageSquareText } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { Tooltip } from '@/components/tooltip';
import type { DateRange, SearchDateRanges } from '@/features/wireframes/date-range';
import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import { MyInquiryHistory } from '@/features/wireframes/my-inquiry-history';
import { MySearchHistory } from '@/features/wireframes/my-search-history';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

type HistoryKind = 'search' | 'inquiry';

interface SearchHistoryProps {
  broadcastRange: DateRange;
  filmingRange: DateRange;
  isDisabled?: boolean;
  onDateRangesChange: (value: SearchDateRanges) => void;
  theme: WireframeTheme;
}

/** 검색 조건과 기록을 한 곳에서 여는 좌측 alternative navigation입니다. */
export function SearchHistory({
  broadcastRange,
  filmingRange,
  isDisabled,
  onDateRangesChange,
  theme,
}: SearchHistoryProps) {
  const [activePanel, setActivePanel] = useState<HistoryKind | 'date' | null>(null);
  const isNavExpanded = activePanel !== null;
  const isHistoryOpen = activePanel === 'search' || activePanel === 'inquiry';
  const [isInquiryDetailOpen, setIsInquiryDetailOpen] = useState(false);
  const dockRef = useRef<HTMLElement>(null);
  const activeTriggerRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!isNavExpanded || isInquiryDetailOpen) return;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setActivePanel(null);
        activeTriggerRef.current?.focus();
      }
    };
    const handleOutside = (event: PointerEvent) => {
      if (dockRef.current?.contains(event.target as Node)) return;
      setActivePanel(null);
    };
    document.addEventListener('keydown', handleEscape);
    document.addEventListener('pointerdown', handleOutside);
    return () => {
      document.removeEventListener('keydown', handleEscape);
      document.removeEventListener('pointerdown', handleOutside);
    };
  }, [isNavExpanded, isInquiryDetailOpen]);

  function closePanel() {
    setActivePanel(null);
    requestAnimationFrame(() => activeTriggerRef.current?.focus());
  }

  function openPanel(kind: HistoryKind) {
    if (activePanel === kind) {
      closePanel();
      return;
    }
    setActivePanel(kind);
  }

  return (
    <>
      <button
        aria-hidden={!isHistoryOpen}
        aria-label="열린 정보 패널 닫기"
        className={styles.pageBackdrop}
        data-open={isHistoryOpen}
        onClick={closePanel}
        tabIndex={isHistoryOpen ? 0 : -1}
        type="button"
      />
      <aside
        aria-label="검색 도구"
        className={styles.navDock}
        data-expanded={isNavExpanded}
        data-has-range={Boolean(broadcastRange.from || filmingRange.from)}
        ref={dockRef}
        onBlur={(event) => {
          if (activePanel !== 'date' || !event.relatedTarget) return;
          if (!event.currentTarget.contains(event.relatedTarget)) setActivePanel(null);
        }}
        onClickCapture={(event) => {
          const button = (event.target as HTMLElement).closest('button');
          if (button?.classList.contains(styles.navAction)) activeTriggerRef.current = button;
        }}
      >
        <div className={styles.navSurface} id="search-tool-nav">
          <div className={styles.navActions}>
            <Tooltip content="기간 설정" isDisabled={activePanel === 'date'}>
              {(descriptionId) => (
                <DateRangePicker
                  isDisabled={isDisabled}
                  isOpen={activePanel === 'date'}
                  triggerDescriptionId={descriptionId}
                  navigationTrigger={{
                    className: styles.navAction,
                    icon: <CalendarDays aria-hidden="true" />,
                  }}
                  onOpenChange={(isOpen) =>
                    setActivePanel((current) =>
                      isOpen ? 'date' : current === 'date' ? null : current,
                    )
                  }
                  onChange={onDateRangesChange}
                  ranges={{ broadcast: broadcastRange, filming: filmingRange }}
                />
              )}
            </Tooltip>
            <Tooltip content="이전 검색 기록" isDisabled={activePanel === 'search'}>
              {(descriptionId) => (
                <button
                  aria-describedby={descriptionId}
                  aria-controls="search-history-panel"
                  aria-expanded={activePanel === 'search'}
                  className={styles.navAction}
                  data-active={activePanel === 'search'}
                  disabled={isDisabled}
                  onClick={() => openPanel('search')}
                  type="button"
                >
                  <History aria-hidden="true" />
                  <span>이전 검색 기록</span>
                </button>
              )}
            </Tooltip>
            <Tooltip content="문의 사항" isDisabled={activePanel === 'inquiry'}>
              {(descriptionId) => (
                <button
                  aria-describedby={descriptionId}
                  aria-controls="inquiry-history-panel"
                  aria-expanded={activePanel === 'inquiry'}
                  className={styles.navAction}
                  data-active={activePanel === 'inquiry'}
                  disabled={isDisabled}
                  onClick={() => openPanel('inquiry')}
                  type="button"
                >
                  <MessageSquareText aria-hidden="true" />
                  <span>문의 사항</span>
                </button>
              )}
            </Tooltip>
          </div>
        </div>
        {(['search', 'inquiry'] as const).map((kind) => (
          <aside
            aria-hidden={activePanel !== kind}
            aria-label={kind === 'search' ? '이전 검색 기록' : '문의 사항'}
            className={styles.detailPanel}
            data-open={activePanel === kind}
            id={`${kind}-history-panel`}
            inert={activePanel !== kind}
            key={kind}
          >
            <button
              aria-label="정보 패널 닫기"
              className={styles.detailClose}
              onClick={closePanel}
              type="button"
            >
              <ChevronLeft aria-hidden="true" />
            </button>
            {kind === 'search' && activePanel === 'search' ? (
              <MySearchHistory theme={theme} onNavigate={closePanel} />
            ) : kind === 'inquiry' && activePanel === 'inquiry' ? (
              <MyInquiryHistory theme={theme} onDetailOpenChange={setIsInquiryDetailOpen} />
            ) : null}
          </aside>
        ))}
      </aside>
    </>
  );
}
