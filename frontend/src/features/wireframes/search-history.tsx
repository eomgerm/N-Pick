'use client';

import { ChevronLeft, Clock3, History, MessageSquareText, Tv, Video } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { DateRange } from '@/features/wireframes/date-range';
import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import { results } from '@/features/wireframes/demo-scenes';
import { MyInquiryHistory } from '@/features/wireframes/my-inquiry-history';
import { ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

type HistoryKind = 'search' | 'inquiry';

interface HistoryItem {
  id: string;
  sceneId: number;
  daysAgo: number;
  query: string;
}

const searchHistory: HistoryItem[] = [
  { id: 'view-1', sceneId: 1, daysAgo: 1, query: '설 연휴 서울역 귀성 인파' },
  { id: 'view-2', sceneId: 2, daysAgo: 2, query: '2025년 추석 경부고속도로 귀성길 정체' },
  { id: 'view-3', sceneId: 3, daysAgo: 3, query: '한국도로공사 교통상황실' },
  { id: 'view-4', sceneId: 2, daysAgo: 5, query: '고속도로 양방향 정체 항공 영상' },
  { id: 'view-5', sceneId: 1, daysAgo: 7, query: '서울역 대합실 귀성객' },
];

function groupByRecency(items: HistoryItem[]) {
  return [
    { label: '오늘', items: items.filter(({ daysAgo }) => daysAgo === 0) },
    { label: '이번 주', items: items.filter(({ daysAgo }) => daysAgo > 0 && daysAgo <= 7) },
  ].filter((group) => group.items.length > 0);
}

interface HistorySectionProps {
  items: HistoryItem[];
  onSelect: (item: HistoryItem) => void;
}

function HistorySection({ items, onSelect }: HistorySectionProps) {
  const title = '이전 검색 기록';

  return (
    <section aria-labelledby="search-history-title" className={styles.section}>
      <h2 className={styles.sectionHeading} id="search-history-title">
        <History aria-hidden="true" />
        <span>{title}</span>
        <small>{items.length}</small>
      </h2>
      <div aria-label={`${title} 목록`} className={styles.listViewport} role="region" tabIndex={0}>
        {groupByRecency(items).map((group) => (
          <div key={group.label}>
            <p aria-hidden="true" className={styles.groupLabel}>
              {group.label}
              <b>{group.items.length}</b>
            </p>
            <ul aria-label={group.label} className={styles.list}>
              {group.items.map((item) => {
                const scene = results.find(({ id }) => id === item.sceneId)!;
                const text = scene.title;
                return (
                  <li key={item.id}>
                    <button
                      aria-haspopup="dialog"
                      className={styles.row}
                      onClick={() => onSelect(item)}
                      type="button"
                    >
                      <span
                        aria-label={`${scene.title} 구간 썸네일`}
                        className={styles.thumbnail}
                        data-scene={scene.id}
                        role="img"
                      />
                      <span className={styles.rowCopy}>
                        <span className={styles.rowTitle} title={text}>
                          {text}
                        </span>
                        <span className={styles.rowSubtitle}>{scene.time}</span>
                      </span>
                      <span className={styles.rowMeta}>
                        <span className={styles.age}>
                          <Clock3 aria-hidden="true" />
                          {item.daysAgo}일 전
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}

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
  const [selectedHistory, setSelectedHistory] = useState<HistoryItem | null>(null);
  const [isInquiryDetailOpen, setIsInquiryDetailOpen] = useState(false);
  const dockRef = useRef<HTMLElement>(null);
  const activeTriggerRef = useRef<HTMLButtonElement | null>(null);
  const selectedScene = results.find(({ id }) => id === selectedHistory?.sceneId);

  useEffect(() => {
    if (!isNavExpanded || selectedHistory || isInquiryDetailOpen) return;
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
  }, [isNavExpanded, selectedHistory, isInquiryDetailOpen]);

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

  function handleClose() {
    setSelectedHistory(null);
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
            {kind === 'search' ? (
              <HistorySection items={searchHistory} onSelect={setSelectedHistory} />
            ) : activePanel === 'inquiry' ? (
              <MyInquiryHistory theme={theme} onDetailOpenChange={setIsInquiryDetailOpen} />
            ) : null}
          </aside>
        ))}
      </aside>
      {selectedScene ? (
        <ScenePreviewDialog onClose={handleClose} result={selectedScene} theme={theme} />
      ) : null}
    </>
  );
}
