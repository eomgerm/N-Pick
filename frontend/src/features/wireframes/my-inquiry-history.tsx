'use client';

import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Clock3, Film, MessageSquareText, X } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { inquiryStatusLabels } from '@/features/wireframes/inquiry-state';
import { getMyInquiries, getMyInquiry, myInquiryKeys } from '@/features/wireframes/my-inquiry-api';
import {
  InquiryDialog,
  SceneDialog,
  type InquiryDetails,
} from '@/features/wireframes/scene-dialogs';
import { formatMediaTime } from '@/features/wireframes/scene-preview-media';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/search-history.module.css';
import dialogStyles from '@/features/wireframes/wireframe.module.css';

interface MyInquiryHistoryProps {
  theme: WireframeTheme;
  onDetailOpenChange: (isOpen: boolean) => void;
}

export function MyInquiryHistory({ theme, onDetailOpenChange }: MyInquiryHistoryProps) {
  const { memberId } = useMember();
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const list = useQuery({
    queryKey: myInquiryKeys.list(memberId, page),
    queryFn: ({ signal }) => getMyInquiries(page, signal),
    staleTime: 0,
  });

  return (
    <>
      <section aria-labelledby="inquiry-history-title" className={styles.section}>
        <h2 className={styles.sectionHeading} id="inquiry-history-title">
          <MessageSquareText aria-hidden="true" />
          <span>문의 사항</span>
          {list.data ? <small>{list.data.totalElements}</small> : null}
        </h2>
        <div aria-label="문의 사항 목록" className={styles.listViewport} role="region" tabIndex={0}>
          {list.isPending ? (
            <p className={styles.stateMessage} role="status">
              문의 목록을 불러오는 중…
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
                문의 목록 다시 시도
              </button>
            </div>
          ) : null}
          {list.data ? (
            <>
              {list.data.items.length === 0 ? (
                <p className={styles.stateMessage} role="status">
                  접수한 문의가 없습니다.
                </p>
              ) : (
                <ul className={styles.list}>
                  {list.data.items.map((item) => (
                    <li key={item.feedbackId}>
                      <button
                        aria-haspopup="dialog"
                        className={styles.row}
                        onClick={() => {
                          setSelectedId(item.feedbackId);
                          onDetailOpenChange(true);
                        }}
                        type="button"
                      >
                        <span className={styles.sceneIcon} aria-hidden="true">
                          <Film />
                        </span>
                        <span className={styles.rowCopy}>
                          <span className={styles.rowTitle} title={item.comment || item.queryText}>
                            {item.comment || item.queryText}
                          </span>
                          <span
                            className={styles.rowSubtitle}
                            title={item.scene.clipTitle ?? undefined}
                          >
                            {item.scene.clipTitle || '제목 없는 영상'}
                          </span>
                        </span>
                        <span className={styles.rowMeta}>
                          <span className={styles.status} data-status={item.status}>
                            {inquiryStatusLabels[item.status]}
                          </span>
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
              {list.data.totalPages > 1 || page > 0 ? (
                <nav aria-label="문의 기록 페이지" className={styles.pagination}>
                  <button
                    aria-label="이전 문의 페이지"
                    className={styles.historyButton}
                    disabled={list.isFetching || page === 0}
                    onClick={() => setPage(page - 1)}
                    type="button"
                  >
                    <ChevronLeft aria-hidden="true" />
                  </button>
                  <span>
                    {page + 1} / {list.data.totalPages}
                  </span>
                  <button
                    aria-label="다음 문의 페이지"
                    className={styles.historyButton}
                    disabled={list.isFetching || !list.data.hasNext}
                    onClick={() => setPage(page + 1)}
                    type="button"
                  >
                    <ChevronRight aria-hidden="true" />
                  </button>
                </nav>
              ) : null}
            </>
          ) : null}
        </div>
      </section>
      {selectedId ? (
        <MyInquiryDetailDialog
          key={selectedId}
          feedbackId={selectedId}
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

interface MyInquiryDetailDialogProps {
  feedbackId: string;
  theme: WireframeTheme;
  onClose: () => void;
}

function MyInquiryDetailDialog({ feedbackId, theme, onClose }: MyInquiryDetailDialogProps) {
  const { memberId } = useMember();
  const detail = useQuery({
    queryKey: myInquiryKeys.detail(memberId, feedbackId),
    queryFn: ({ signal }) => getMyInquiry(feedbackId, signal),
    staleTime: 0,
  });
  if (detail.isSuccess) {
    const inquiry = detail.data;
    const history: InquiryDetails =
      inquiry.status === 'closed'
        ? {
            status: 'closed',
            comment: inquiry.comment ?? '',
            resolution: inquiry.resolution,
            resolutionSummary: inquiry.resolutionNote,
            snapshotUnavailable: true,
          }
        : { status: inquiry.status, comment: inquiry.comment ?? '', snapshotUnavailable: true };
    return (
      <InquiryDialog
        theme={theme}
        query={inquiry.queryText}
        history={history}
        result={{
          id: inquiry.scene.sceneId,
          title: inquiry.scene.clipTitle || '제목 없는 영상',
          time: `${formatMediaTime(inquiry.scene.startTimeMs / 1000)} – ${formatMediaTime(inquiry.scene.endTimeMs / 1000)}`,
        }}
        onClose={onClose}
      />
    );
  }
  return (
    <SceneDialog
      theme={theme}
      className={dialogStyles.inquiryDetailModal}
      labelledBy="inquiry-history-detail-title"
      onClose={onClose}
    >
      <header className={dialogStyles.inquiryDetailHeader}>
        <h2 id="inquiry-history-detail-title">문의 상세</h2>
        <button
          aria-label="문의 창 닫기"
          className={dialogStyles.inquiryDetailClose}
          onClick={onClose}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </header>
      <div className={dialogStyles.inquiryDetailBody}>
        {detail.isPending ? (
          <p role="status">문의 상세를 불러오는 중…</p>
        ) : (
          <>
            <ApiErrorNotice error={detail.error} />
            <button
              className={styles.historyButton}
              disabled={detail.isFetching}
              onClick={() => void detail.refetch()}
              type="button"
            >
              문의 상세 다시 시도
            </button>
          </>
        )}
      </div>
    </SceneDialog>
  );
}
