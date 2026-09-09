'use client';

import { ChevronUp, Clock3, History, MessageSquareText } from 'lucide-react';
import { useRef, useState } from 'react';

import { results } from '@/features/wireframes/demo-scenes';
import {
  InquiryDialog,
  ScenePreviewDialog,
  type InquiryDetails,
} from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

type HistoryKind = 'search' | 'inquiry';
type InquiryStatus = InquiryDetails['status'];

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
    inquiry: { comment: '고속도로를 검색했는데 역 내부 장면이 나와요', status: 'pending' },
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
      status: 'resolved',
      resolution:
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
      status: 'resolved',
      resolution:
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
      status: 'resolved',
      resolution:
        '원활한 소통 상황을 찾는 검색에서 정체 구간이 노출되지 않도록 제외했습니다. 동일한 검색 조건에서 다른 후보 구간이 표시되는 것을 확인했습니다.',
    },
  },
];

const statusLabels: Record<InquiryStatus, string> = {
  pending: '대기',
  reviewing: '처리 중',
  resolved: '완료',
};

interface HistorySheetProps {
  kind: HistoryKind;
  title: string;
  items: HistoryItem[];
  isExpanded: boolean;
  onToggle: () => void;
  onSelect: (item: HistoryItem) => void;
}

function HistorySheet({ kind, title, items, isExpanded, onToggle, onSelect }: HistorySheetProps) {
  const toggleRef = useRef<HTMLButtonElement>(null);

  return (
    <section
      aria-labelledby={`${kind}-history-title`}
      className={styles.sheet}
      data-expanded={isExpanded}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && isExpanded) {
          onToggle();
          toggleRef.current?.focus();
        }
      }}
    >
      <span aria-hidden="true" className={styles.handle} />
      <h2 className={styles.heading} id={`${kind}-history-title`}>
        <button
          aria-controls={`${kind}-history-list`}
          aria-expanded={isExpanded}
          aria-label={`${title} ${isExpanded ? '접기' : '펼치기'}`}
          className={styles.toggle}
          onClick={onToggle}
          ref={toggleRef}
          type="button"
        >
          {kind === 'search' ? (
            <History aria-hidden="true" />
          ) : (
            <MessageSquareText aria-hidden="true" />
          )}
          <span>{title}</span>
          <small>{items.length}</small>
          <ChevronUp aria-hidden="true" className={styles.chevron} />
        </button>
      </h2>
      <div
        aria-label={`${title} 목록`}
        className={styles.listViewport}
        id={`${kind}-history-list`}
        role="region"
        tabIndex={isExpanded ? 0 : -1}
      >
        <ul className={styles.list}>
          {items.map((item) => {
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
                  onFocus={(event) => {
                    if (!isExpanded && event.currentTarget.matches(':focus-visible')) onToggle();
                  }}
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
                  {item.inquiry ? (
                    <span className={styles.status} data-status={item.inquiry.status}>
                      {statusLabels[item.inquiry.status]}
                    </span>
                  ) : null}
                  <span className={styles.age}>
                    <Clock3 aria-hidden="true" />
                    {item.daysAgo === 0 ? '오늘' : `${item.daysAgo}일 전`}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
      <div aria-hidden="true" className={styles.fade} />
    </section>
  );
}

interface SearchHistoryProps {
  theme: WireframeTheme;
}

export function SearchHistory({ theme }: SearchHistoryProps) {
  const [expandedSheet, setExpandedSheet] = useState<HistoryKind | null>(null);
  const [inquiries, setInquiries] = useState(inquiryHistory);
  const [selectedHistory, setSelectedHistory] = useState<{ kind: HistoryKind; id: string } | null>(
    null,
  );
  const [isCreatingInquiry, setIsCreatingInquiry] = useState(false);
  const [submittedSceneIds, setSubmittedSceneIds] = useState<number[]>([]);
  const [notice, setNotice] = useState('');
  const selectedItem = selectedHistory
    ? (selectedHistory.kind === 'search' ? searchHistory : inquiries).find(
        ({ id }) => id === selectedHistory.id,
      )
    : undefined;
  const selectedScene = results.find(({ id }) => id === selectedItem?.sceneId);

  function handleToggle(kind: HistoryKind) {
    setExpandedSheet((current) => (current === kind ? null : kind));
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
        inquiry: { comment, status: 'pending' },
      },
      ...current,
    ]);
    setSubmittedSceneIds((current) => [...current, selectedScene.id]);
    setNotice('문의가 대기 상태로 접수되었습니다. 문의 기록에서 확인할 수 있어요.');
    handleClose();
  }

  return (
    <>
      <div className={styles.dock}>
        <div className={styles.slot}>
          <HistorySheet
            kind="search"
            title="이전 검색 기록"
            items={searchHistory}
            isExpanded={expandedSheet === 'search'}
            onToggle={() => handleToggle('search')}
            onSelect={(item) => handleSelect('search', item)}
          />
        </div>
        <div className={styles.slot}>
          <HistorySheet
            kind="inquiry"
            title="문의 기록"
            items={inquiries}
            isExpanded={expandedSheet === 'inquiry'}
            onToggle={() => handleToggle('inquiry')}
            onSelect={(item) => handleSelect('inquiry', item)}
          />
        </div>
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
