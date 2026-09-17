'use client';

import { ChevronLeft, Clock3, History, MessageSquareText, Tv, Video } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { DateRange } from '@/features/wireframes/date-range';
import { DateRangePicker } from '@/features/wireframes/date-range-picker';
import { results } from '@/features/wireframes/demo-scenes';
import { inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import {
  InquiryDialog,
  ScenePreviewDialog,
  type InquiryDetails,
} from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

type HistoryKind = 'search' | 'inquiry';

interface HistoryItem {
  id: string;
  sceneId: number;
  daysAgo: number;
  query: string;
  inquiry?: InquiryDetails;
}

const searchHistory: HistoryItem[] = [
  { id: 'view-1', sceneId: 1, daysAgo: 1, query: '설 연휴 서울역 귀성 인파' },
  { id: 'view-2', sceneId: 2, daysAgo: 2, query: '2025년 추석 경부고속도로 귀성길 정체' },
  { id: 'view-3', sceneId: 3, daysAgo: 3, query: '한국도로공사 교통상황실' },
  { id: 'view-4', sceneId: 2, daysAgo: 5, query: '고속도로 양방향 정체 항공 영상' },
  { id: 'view-5', sceneId: 1, daysAgo: 7, query: '서울역 대합실 귀성객' },
];

const inquiryHistory: HistoryItem[] = [
  {
    id: 'inquiry-1',
    sceneId: 1,
    daysAgo: 1,
    query: '2025년 추석 경부고속도로 귀성길 정체',
    inquiry: { comment: '고속도로를 검색했는데 역 내부 장면이 나와요', status: 'open' },
  },
  {
    id: 'inquiry-2',
    sceneId: 2,
    daysAgo: 2,
    query: '2026년 설 연휴 고속도로 정체',
    inquiry: { comment: '요청한 연도와 다른 교통 자료화면이에요', status: 'reviewing' },
  },
  {
    id: 'inquiry-3',
    sceneId: 3,
    daysAgo: 3,
    query: '귀성길 고속도로 외경',
    inquiry: {
      comment: '도로 외경 대신 교통상황실이 검색돼요',
      status: 'closed',
      resolution: 'exclude_scene',
      resolutionSummary: '같은 검색 조건에서 해당 구간이 노출되지 않도록 제외했습니다.',
    },
  },
  {
    id: 'inquiry-4',
    sceneId: 1,
    daysAgo: 5,
    query: '서울역 귀성 인파 전경',
    inquiry: {
      comment: '인파 전경을 찾았는데 인터뷰 장면이 포함돼요',
      status: 'closed',
      resolution: 'exclude_scene',
      resolutionSummary: '같은 조건으로 다시 검색해 해당 구간이 제외되는 것을 확인했습니다.',
    },
  },
  {
    id: 'inquiry-5',
    sceneId: 2,
    daysAgo: 7,
    query: '원활한 경부고속도로 소통 상황',
    inquiry: {
      comment: '같은 검색에 적합하지 않은 구간이 보여요',
      status: 'closed',
      resolution: 'exclude_scene',
      resolutionSummary: '같은 검색 조건에서 다른 후보 구간이 표시되는 것을 확인했습니다.',
    },
  },
];

function groupByRecency(items: HistoryItem[]) {
  return [
    { label: '오늘', items: items.filter(({ daysAgo }) => daysAgo === 0) },
    { label: '이번 주', items: items.filter(({ daysAgo }) => daysAgo > 0 && daysAgo <= 7) },
  ].filter((group) => group.items.length > 0);
}

interface HistorySectionProps {
  kind: HistoryKind;
  items: HistoryItem[];
  onSelect: (item: HistoryItem) => void;
}

function HistorySection({ kind, items, onSelect }: HistorySectionProps) {
  const title = kind === 'search' ? '이전 검색 기록' : '문의 사항';
  const Icon = kind === 'search' ? History : MessageSquareText;

  return (
    <section aria-labelledby={`${kind}-history-title`} className={styles.section}>
      <h2 className={styles.sectionHeading} id={`${kind}-history-title`}>
        <Icon aria-hidden="true" />
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
                const text = item.inquiry?.comment || scene.title;
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
                        <span className={styles.rowSubtitle}>
                          {kind === 'search' ? scene.time : scene.title}
                        </span>
                      </span>
                      <span className={styles.rowMeta}>
                        {item.inquiry ? (
                          <span className={styles.status} data-status={item.inquiry.status}>
                            {inquiryStatusLabels[item.inquiry.status]}
                          </span>
                        ) : null}
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
  const [inquiries, setInquiries] = useState(inquiryHistory);
  const [selectedHistory, setSelectedHistory] = useState<{ kind: HistoryKind; id: string } | null>(
    null,
  );
  const [isCreatingInquiry, setIsCreatingInquiry] = useState(false);
  const [submittedSceneIds, setSubmittedSceneIds] = useState<number[]>([]);
  const [notice, setNotice] = useState('');
  const dockRef = useRef<HTMLElement>(null);
  const activeTriggerRef = useRef<HTMLButtonElement | null>(null);
  const selectedItem = selectedHistory
    ? (selectedHistory.kind === 'search' ? searchHistory : inquiries).find(
        ({ id }) => id === selectedHistory.id,
      )
    : undefined;
  const selectedScene = results.find(({ id }) => id === selectedItem?.sceneId);

  useEffect(() => {
    if (!isNavExpanded || selectedHistory) return;
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
  }, [isNavExpanded, selectedHistory]);

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
    setIsCreatingInquiry(false);
  }

  function handleInquiryCreate(comment: string) {
    if (!selectedItem || !selectedScene) return;
    setInquiries((current) => [
      {
        id: `inquiry-${current.length + 1}`,
        sceneId: selectedScene.id,
        daysAgo: 0,
        query: selectedItem.query,
        inquiry: { comment, status: 'open' },
      },
      ...current,
    ]);
    setSubmittedSceneIds((current) => [...current, selectedScene.id]);
    setNotice('문의가 접수되었습니다. 문의 사항에서 확인할 수 있어요.');
    handleClose();
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
            <HistorySection
              items={kind === 'search' ? searchHistory : inquiries}
              kind={kind}
              onSelect={(item) => {
                setNotice('');
                setSelectedHistory({ kind, id: item.id });
              }}
            />
          </aside>
        ))}
      </aside>
      <p className={styles.notice} role="status">
        {notice}
      </p>
      {selectedScene && selectedHistory?.kind === 'search' && !isCreatingInquiry ? (
        <ScenePreviewDialog
          isSubmitted={submittedSceneIds.includes(selectedScene.id)}
          onClose={handleClose}
          onInquiry={() => setIsCreatingInquiry(true)}
          result={selectedScene}
          theme={theme}
        />
      ) : null}
      {selectedScene && selectedItem && (selectedItem.inquiry || isCreatingInquiry) ? (
        <InquiryDialog
          history={selectedItem.inquiry}
          key={isCreatingInquiry ? `new-${selectedItem.id}` : selectedItem.id}
          onClose={handleClose}
          onSubmit={handleInquiryCreate}
          query={selectedItem.query}
          result={selectedScene}
          theme={theme}
        />
      ) : null}
    </>
  );
}
