'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Clock3, FileSearch, History, Trash2 } from 'lucide-react';
import { useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { PageNumbers } from '@/features/wireframes/list-pagination-controls';
import {
  deleteMySearchHistory,
  getMySearchHistory,
  type MySearchHistoryItem,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
import { MySearchHistoryDeleteDialog } from '@/features/wireframes/my-search-history-delete-dialog';
import { MySearchHistoryDetail } from '@/features/wireframes/my-search-history-detail';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';

interface MySearchHistoryProps {
  theme: WireframeTheme;
  onDetailOpenChange: (isOpen: boolean) => void;
  onSelect: (query: string) => void;
}

export function MySearchHistory({ theme, onDetailOpenChange, onSelect }: MySearchHistoryProps) {
  const { memberId } = useMember();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<MySearchHistoryItem | null>(null);
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

  function closeDeleteDialog() {
    if (remove.isPending) return;
    remove.reset();
    setPendingDelete(null);
  }

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
                        onClick={() => onSelect(item.queryText)}
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
                          aria-label={`${item.queryText} 검색 기록 상세 보기`}
                          aria-haspopup="dialog"
                          className={styles.rowAction}
                          onClick={() => {
                            setSelectedId(item.searchExecutionId);
                            onDetailOpenChange(true);
                          }}
                          type="button"
                        >
                          <FileSearch aria-hidden="true" />
                          <span>상세</span>
                        </button>
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
        </div>
        {page > 0 || (list.data?.hasNext ?? false) ? (
          // 목록이 길어도 스크롤 없이 닿도록 스크롤 영역 밖에 둔다.
          <nav aria-label="검색 기록 페이지" className={styles.pagination}>
            <PageNumbers
              isCompact
              isDisabled={list.isFetching}
              page={page + 1}
              totalPages={list.data?.totalPages ?? page + 1}
              onPageChange={(next) => setPage(next - 1)}
            />
          </nav>
        ) : null}
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
    </>
  );
}
