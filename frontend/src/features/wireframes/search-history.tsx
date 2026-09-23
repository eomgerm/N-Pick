'use client';

import { ChevronLeft, History, MessageSquareText, Tv, Video } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { DateRange } from '@/features/wireframes/date-range';
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
  onBroadcastChange: (value: DateRange) => void;
  onFilmingChange: (value: DateRange) => void;
  theme: WireframeTheme;
}

/** 검색 조건과 기록을 한 곳에서 여는 좌측 alternative navigation입니다. */
export function SearchHistory({
  broadcastRange,
  filmingRange,
  isDisabled,
  onBroadcastChange,
  onFilmingChange,
  theme,
}: SearchHistoryProps) {
  const [isNavExpanded, setIsNavExpanded] = useState(false);
  const [activePanel, setActivePanel] = useState<HistoryKind | null>(null);
  const [isInquiryDetailOpen, setIsInquiryDetailOpen] = useState(false);
  const dockRef = useRef<HTMLElement>(null);
  const activeTriggerRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!isNavExpanded || isInquiryDetailOpen) return;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setActivePanel(null);
        setIsNavExpanded(false);
        activeTriggerRef.current?.focus();
      }
    };
    const handleOutside = (event: PointerEvent) => {
      if (dockRef.current?.contains(event.target as Node)) return;
      setActivePanel(null);
      setIsNavExpanded(false);
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
    setIsNavExpanded(false);
    requestAnimationFrame(() => activeTriggerRef.current?.focus());
  }

  function openPanel(kind: HistoryKind) {
    if (activePanel === kind) {
      closePanel();
      return;
    }
    setIsNavExpanded(true);
    setActivePanel(kind);
  }

  return (
    <>
      <button
        aria-hidden={!activePanel}
        aria-label="열린 정보 패널 닫기"
        className={styles.pageBackdrop}
        data-open={Boolean(activePanel)}
        onClick={closePanel}
        tabIndex={activePanel ? 0 : -1}
        type="button"
      />
      <aside
        aria-label="검색 도구"
        className={styles.navDock}
        data-expanded={isNavExpanded}
        data-has-range={Boolean(broadcastRange.from || filmingRange.from)}
        ref={dockRef}
        onClickCapture={(event) => {
          const button = (event.target as HTMLElement).closest('button');
          if (button?.classList.contains(styles.navAction)) activeTriggerRef.current = button;
        }}
      >
        <div className={styles.navSurface} id="search-tool-nav">
          <div className={styles.navActions}>
            <DateRangePicker
              isDisabled={isDisabled}
              label="방송일"
              navigationTrigger={{
                className: styles.navAction,
                icon: <Tv aria-hidden="true" />,
                onOpen: () => {
                  setIsNavExpanded(true);
                  setActivePanel(null);
                },
              }}
              onChange={onBroadcastChange}
              value={broadcastRange}
            />
            <DateRangePicker
              isDisabled={isDisabled}
              label="촬영일"
              navigationTrigger={{
                className: styles.navAction,
                icon: <Video aria-hidden="true" />,
                onOpen: () => {
                  setIsNavExpanded(true);
                  setActivePanel(null);
                },
              }}
              onChange={onFilmingChange}
              value={filmingRange}
            />
            <button
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
            <button
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
