'use client';

import { ArrowLeft, ArrowRight, Clock3, History, MessageSquareText } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

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

// 화면 비교용 예시 기록. 실제 검색·문의 이력 API가 연결되면 교체합니다.
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
      resolutionSummary:
        '문의하신 검색어에서 교통상황실 구간이 노출되지 않도록 제외했습니다. 동일한 검색어와 필터로 다시 검색하여 해당 구간이 제외되는 것을 확인했습니다.',
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
      resolutionSummary:
        '문의하신 구간을 확인하고, 해당 검색어와 필터의 결과에서 제외했습니다. 같은 조건으로 재검색하여 제외 결과를 확인했습니다.',
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
      resolutionSummary:
        '원활한 소통 상황을 찾는 검색에서 정체 구간이 노출되지 않도록 제외했습니다. 동일한 검색 조건에서 다른 후보 구간이 표시되는 것을 확인했습니다.',
    },
  },
];

// 펼친 시트에서 기록을 시기별로 묶어 보여 줍니다.
function groupByRecency(items: HistoryItem[]) {
  return [
    { label: '오늘', items: items.filter(({ daysAgo }) => daysAgo === 0) },
    { label: '이번 주', items: items.filter(({ daysAgo }) => daysAgo > 0 && daysAgo <= 7) },
    { label: '지난 기록', items: items.filter(({ daysAgo }) => daysAgo > 7) },
  ].filter((group) => group.items.length > 0);
}

interface HistorySectionProps {
  kind: HistoryKind;
  title: string;
  items: HistoryItem[];
  onSelect: (item: HistoryItem) => void;
}

function HistorySection({ kind, title, items, onSelect }: HistorySectionProps) {
  return (
    <section aria-labelledby={`${kind}-history-title`} className={styles.section}>
      <h2 className={styles.sectionHeading} id={`${kind}-history-title`}>
        {kind === 'search' ? (
          <History aria-hidden="true" />
        ) : (
          <MessageSquareText aria-hidden="true" />
        )}
        <span>{title}</span>
        <small>{items.length}</small>
      </h2>
      <div
        aria-label={`${title} 목록`}
        className={styles.listViewport}
        id={`${kind}-history-list`}
        role="region"
        tabIndex={0}
      >
        {groupByRecency(items).map((group) => (
          <div key={group.label}>
            <p aria-hidden="true" className={styles.groupLabel}>
              {group.label}
              <b>{group.items.length}</b>
            </p>
            <ul aria-label={group.label} className={styles.list}>
              {group.items.map((item) => {
                const scene = results.find(({ id }) => id === item.sceneId)!;
                const text =
                  item.inquiry?.comment || (item.inquiry ? '설명 없이 접수한 문의' : scene.title);
                return (
                  <li key={item.id}>
                    <button
                      aria-haspopup="dialog"
                      className={styles.row}
                      data-kind={kind}
                      onClick={() => onSelect(item)}
                      type="button"
                    >
                      <span
                        aria-label={`${scene.title} 구간 썸네일`}
                        className={styles.thumbnail}
                        data-scene={scene.id}
                        role="img"
                      >
                        <span aria-hidden="true">{scene.time}</span>
                      </span>
                      <span className={styles.rowCopy}>
                        <span className={styles.rowTitle} title={text}>
                          {text}
                        </span>
                        <span className={styles.rowSubtitle}>
                          {kind === 'search' ? `시청한 구간 · ${scene.time}` : scene.title}
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
                          {item.daysAgo === 0 ? '오늘' : `${item.daysAgo}일 전`}
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
  theme: WireframeTheme;
}

export function SearchHistory({ theme }: SearchHistoryProps) {
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [inquiries, setInquiries] = useState(inquiryHistory);
  const [selectedHistory, setSelectedHistory] = useState<{ kind: HistoryKind; id: string } | null>(
    null,
  );
  const [isCreatingInquiry, setIsCreatingInquiry] = useState(false);
  const [submittedSceneIds, setSubmittedSceneIds] = useState<number[]>([]);
  const [notice, setNotice] = useState('');
  const sidebarToggleRef = useRef<HTMLButtonElement>(null);
  const selectedItem = selectedHistory
    ? (selectedHistory.kind === 'search' ? searchHistory : inquiries).find(
        ({ id }) => id === selectedHistory.id,
      )
    : undefined;
  const selectedScene = results.find(({ id }) => id === selectedItem?.sceneId);

  useEffect(() => {
    if (!isSidebarOpen) return;
    const body = document.body;
    const previousOverflow = body.style.overflow;
    const previousPaddingRight = body.style.paddingRight;
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) {
      const currentPadding = Number.parseFloat(window.getComputedStyle(body).paddingRight) || 0;
      body.style.paddingRight = `${currentPadding + scrollbarWidth}px`;
    }
    return () => {
      body.style.overflow = previousOverflow;
      body.style.paddingRight = previousPaddingRight;
    };
  }, [isSidebarOpen]);

  function closeSidebar() {
    setIsSidebarOpen(false);
    requestAnimationFrame(() => sidebarToggleRef.current?.focus());
  }

  function handleClose() {
    setSelectedHistory(null);
    setIsCreatingInquiry(false);
  }

  function handleSelect(kind: HistoryKind, item: HistoryItem) {
    setNotice('');
    setIsCreatingInquiry(false);
    setSelectedHistory({ kind, id: item.id });
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
    setNotice('문의가 접수되었습니다. 문의 기록에서 확인할 수 있어요.');
    handleClose();
  }

  return (
    <>
      <button
        aria-hidden={!isSidebarOpen}
        aria-label="검색 기록 사이드바 닫기"
        className={styles.pageBackdrop}
        data-open={isSidebarOpen}
        onClick={closeSidebar}
        onKeyDown={(event) => {
          if (event.key === 'Escape') closeSidebar();
        }}
        tabIndex={isSidebarOpen ? 0 : -1}
        type="button"
      />
      <div
        className={styles.sidebarDock}
        data-open={isSidebarOpen}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && isSidebarOpen) {
            closeSidebar();
          }
        }}
      >
        <aside
          aria-hidden={!isSidebarOpen}
          className={styles.sidebar}
          id="search-history-sidebar"
          inert={!isSidebarOpen}
        >
          <HistorySection
            kind="search"
            title="이전 검색 기록"
            items={searchHistory}
            onSelect={(item) => handleSelect('search', item)}
          />
          <HistorySection
            kind="inquiry"
            title="문의 기록"
            items={inquiries}
            onSelect={(item) => handleSelect('inquiry', item)}
          />
        </aside>
        <button
          aria-controls="search-history-sidebar"
          aria-expanded={isSidebarOpen}
          aria-label={isSidebarOpen ? '검색 기록 사이드바 접기' : '검색 기록 사이드바 펼치기'}
          className={styles.sidebarToggle}
          onClick={() => (isSidebarOpen ? closeSidebar() : setIsSidebarOpen(true))}
          ref={sidebarToggleRef}
          type="button"
        >
          <span>{isSidebarOpen ? '기록 접기' : '기록 열기'}</span>
          {isSidebarOpen ? <ArrowLeft aria-hidden="true" /> : <ArrowRight aria-hidden="true" />}
        </button>
      </div>
      <p className={styles.notice} role="status">
        {notice}
      </p>
      {selectedScene && selectedHistory?.kind === 'search' && !isCreatingInquiry ? (
        <ScenePreviewDialog
          result={selectedScene}
          theme={theme}
          isSubmitted={submittedSceneIds.includes(selectedScene.id)}
          onInquiry={() => setIsCreatingInquiry(true)}
          onClose={handleClose}
        />
      ) : null}
      {selectedScene && selectedItem && (selectedItem.inquiry || isCreatingInquiry) ? (
        <InquiryDialog
          key={isCreatingInquiry ? `new-${selectedItem.id}` : selectedItem.id}
          result={selectedScene}
          theme={theme}
          query={selectedItem.query}
          history={selectedItem.inquiry}
          onSubmit={handleInquiryCreate}
          onClose={handleClose}
        />
      ) : null}
    </>
  );
}
