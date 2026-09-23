'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Clock3, History, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import {
  clearMySearchHistory,
  deleteMySearchHistory,
  getMySearchHistory,
  type MySearchHistoryItem,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
import { MySearchHistoryClearDialog } from '@/features/wireframes/my-search-history-clear-dialog';
import { MySearchHistoryDeleteDialog } from '@/features/wireframes/my-search-history-delete-dialog';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

interface MySearchHistoryProps {
  theme: WireframeTheme;
  /** 기록 행을 눌러 결과 화면으로 이동하기 직전에 호출한다. 패널을 열어 둔 채로 이동하면
   *  전환이 끝날 때까지 패널이 그대로 남고, 이미 열려 있는 기록을 다시 눌러도 반응이 없어 보인다. */
  onNavigate: () => void;
}

export function MySearchHistory({ theme, onNavigate }: MySearchHistoryProps) {
  const router = useRouter();
  const { memberId } = useMember();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [pendingDelete, setPendingDelete] = useState<MySearchHistoryItem | null>(null);
  const [isClearOpen, setIsClearOpen] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const list = useQuery({
    queryKey: mySearchHistoryKeys.list(memberId, page),
    queryFn: ({ signal }) => getMySearchHistory(page, signal),
    staleTime: 0,
  });

  const remove = useMutation({
    mutationFn: (executionId: string) => deleteMySearchHistory(executionId),
    onSuccess: async () => {
      // 이 페이지의 마지막 항목을 지우면 남는 것이 없다. 총계가 줄어 페이지 자체가 사라지므로 한 칸 물러난다.
      // invalidate 보다 먼저 옮겨야 한다 — 뒤에 두면 비어 있는 현 페이지를 한 번 받아 렌더한 뒤에 물러난다.
      if (page > 0 && list.data?.items.length === 1) setPage(page - 1);
      // 한 건을 지워도 뒷 페이지의 구성과 총계가 모두 밀리므로 이 사용자의 목록 전체를 다시 읽는다.
      await queryClient.invalidateQueries({ queryKey: mySearchHistoryKeys.all(memberId) });
      setPendingDelete(null);
      // 다이얼로그가 닫히고 언마운트된 뒤에 옮긴다. 모달이 열려 있는 동안은 바깥이 inert 라 focus() 가 무시되고,
      // SceneDialog 가 되돌리려는 원래 버튼은 지워진 행과 함께 사라져 포커스가 <body> 로 떨어진다.
      // search-history.tsx 의 closePanel() 이 같은 이유로 rAF 를 쓴다.
      requestAnimationFrame(() => listRef.current?.focus({ preventScroll: true }));
    },
  });

  const clearAll = useMutation({
    mutationFn: () => clearMySearchHistory(),
    onSuccess: async () => {
      // 전체를 지우면 뒷 페이지도 모두 사라지므로 첫 페이지로 되돌린다. invalidate 보다 먼저 옮겨
      // 빈 현재 페이지를 한 번 받아 렌더하는 일을 없앤다.
      setPage(0);
      await queryClient.invalidateQueries({ queryKey: mySearchHistoryKeys.all(memberId) });
      setIsClearOpen(false);
      // 건별 삭제와 같은 이유로 다이얼로그가 언마운트된 뒤 rAF 로 목록에 포커스를 돌린다 — 모달이 열린 동안은
      // 바깥이 inert 라 focus() 가 무시되고, 놓치면 포커스가 <body> 로 떨어진다.
      requestAnimationFrame(() => listRef.current?.focus({ preventScroll: true }));
    },
  });

  function closeDeleteDialog() {
    if (remove.isPending) return;
    remove.reset();
    setPendingDelete(null);
  }

  function closeClearDialog() {
    if (clearAll.isPending) return;
    clearAll.reset();
    setIsClearOpen(false);
  }

  return (
    <>
      <section aria-labelledby="search-history-title" className={styles.section}>
        <div className={styles.sectionHeader}>
          <h2 className={styles.sectionHeading} id="search-history-title">
            <History aria-hidden="true" />
            <span>이전 검색 기록</span>
            {list.data ? <small>{list.data.totalElements}</small> : null}
          </h2>
          {list.data && list.data.totalElements > 0 ? (
            <button
              aria-haspopup="dialog"
              className={styles.clearAllButton}
              onClick={() => {
                clearAll.reset();
                setIsClearOpen(true);
              }}
              type="button"
            >
              <Trash2 aria-hidden="true" />
              <span>전체 삭제</span>
            </button>
          ) : null}
        </div>
        <div
          aria-label="이전 검색 기록 목록"
          className={styles.listViewport}
          ref={listRef}
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
                    <li className={styles.rowItem} key={item.searchExecutionId}>
                      <button
                        className={styles.row}
                        type="button"
                        onClick={() => {
                          onNavigate();
                          router.push(`/search/results?historyId=${item.searchExecutionId}`);
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
                      <span className={styles.rowActions}>
                        <button
                          aria-label={`${item.queryText} 검색 기록 삭제`}
                          aria-haspopup="dialog"
                          className={`${styles.rowAction} ${styles.rowDelete}`}
                          onClick={() => {
                            remove.reset();
                            setPendingDelete(item);
                          }}
                          type="button"
                        >
                          <Trash2 aria-hidden="true" />
                          <span>삭제</span>
                        </button>
                      </span>
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
      {pendingDelete ? (
        <MySearchHistoryDeleteDialog
          error={remove.error}
          isDeleting={remove.isPending}
          onCancel={closeDeleteDialog}
          onConfirm={() => remove.mutate(pendingDelete.searchExecutionId)}
          queryText={pendingDelete.queryText}
          theme={theme}
        />
      ) : null}
      {isClearOpen && list.data ? (
        <MySearchHistoryClearDialog
          error={clearAll.error}
          isClearing={clearAll.isPending}
          onCancel={closeClearDialog}
          onConfirm={() => clearAll.mutate()}
          theme={theme}
          totalElements={list.data.totalElements}
        />
      ) : null}
    </>
  );
}
