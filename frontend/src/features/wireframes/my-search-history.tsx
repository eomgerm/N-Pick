'use client';

import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Clock3, History } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import {
  getMySearchHistory,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
import { MySearchHistoryDetail } from '@/features/wireframes/my-search-history-detail';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

interface MySearchHistoryProps {
  theme: WireframeTheme;
  onDetailOpenChange: (isOpen: boolean) => void;
}

export function MySearchHistory({ theme, onDetailOpenChange }: MySearchHistoryProps) {
  const { memberId } = useMember();
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const list = useQuery({
    queryKey: mySearchHistoryKeys.list(memberId, page),
    queryFn: ({ signal }) => getMySearchHistory(page, signal),
    staleTime: 0,
  });

  return (
    <>
      <section aria-labelledby="search-history-title" className={styles.section}>
        <h2 className={styles.sectionHeading} id="search-history-title">
          <History aria-hidden="true" />
          <span>이전 검색 기록</span>
          {list.data ? <small>{list.data.totalElements}</small> : null}
        </h2>
        <div
          aria-label="이전 검색 기록 목록"
          className={styles.listViewport}
          role="region"
          tabIndex={0}
        >
          {list.isPending ? (
            <p className={styles.stateMessage} role="status">
              검색 기록을 불러오는 중…
            </p>
          ) : null}
          {list.isError ? (
            <div className={styles.stateMessage}>
              <ApiErrorNotice error={list.error} />
              {list.data ? <p>마지막으로 확인한 목록입니다.</p> : null}
              <button
                className={styles.historyButton}
                disabled={list.isFetching}
                onClick={() => void list.refetch()}
                type="button"
              >
                검색 기록 다시 시도
              </button>
            </div>
          ) : null}
          {list.data ? (
            <>
              {list.data.items.length === 0 ? (
                <p className={styles.stateMessage} role="status">
                  {page === 0
                    ? '아직 검색 기록이 없습니다.'
                    : '이 페이지에는 검색 기록이 없습니다.'}
                </p>
              ) : (
                <ul className={styles.list}>
                  {list.data.items.map((item) => (
                    <li key={item.searchExecutionId}>
                      <button
                        aria-haspopup="dialog"
                        className={styles.row}
                        type="button"
                        onClick={() => {
                          setSelectedId(item.searchExecutionId);
                          onDetailOpenChange(true);
                        }}
                      >
                        <span className={styles.sceneIcon} aria-hidden="true">
                          <History />
                        </span>
                        <span className={styles.rowCopy}>
                          <span className={styles.rowTitle} title={item.queryText}>
                            {item.queryText}
                          </span>
                          <span className={styles.rowSubtitle}>
                            {item.snapshotStatus === 'unavailable'
                              ? '당시 결과 복원 불가'
                              : `검색 결과 ${item.resultCount}건`}
                          </span>
                          {item.representativeResult ? (
                            <span
                              className={styles.rowSubtitle}
                              title={item.representativeResult.displayName ?? undefined}
                            >
                              {item.representativeResult.displayName ?? '제목 없는 영상'}
                            </span>
                          ) : null}
                        </span>
                        <span className={styles.rowMeta}>
                          {item.status === 'degraded' ? (
                            <span className={styles.status}>일부 기능 누락</span>
                          ) : null}
                          <span className={styles.age}>
                            <Clock3 aria-hidden="true" />
                            <time dateTime={item.createdAt}>
                              {new Date(item.createdAt).toLocaleDateString('ko-KR', {
                                timeZone: 'Asia/Seoul',
                              })}
                            </time>
                          </span>
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          ) : null}
          {page > 0 || (list.data?.hasNext ?? false) ? (
            <nav aria-label="검색 기록 페이지" className={styles.pagination}>
              <button
                aria-label="이전 검색 기록 페이지"
                className={styles.historyButton}
                disabled={list.isFetching || page === 0}
                onClick={() => setPage(page - 1)}
                type="button"
              >
                <ChevronLeft aria-hidden="true" />
              </button>
              <span>
                {page + 1} 페이지
                {list.data && page < list.data.totalPages ? ` / ${list.data.totalPages}` : ''}
              </span>
              <button
                aria-label="다음 검색 기록 페이지"
                className={styles.historyButton}
                disabled={list.isFetching || !list.data?.hasNext}
                onClick={() => setPage(page + 1)}
                type="button"
              >
                <ChevronRight aria-hidden="true" />
              </button>
            </nav>
          ) : null}
        </div>
      </section>
      {selectedId ? (
        <MySearchHistoryDetail
          key={selectedId}
          executionId={selectedId}
          theme={theme}
          onClose={() => {
            setSelectedId(null);
            onDetailOpenChange(false);
          }}
        />
      ) : null}
    </>
  );
}
