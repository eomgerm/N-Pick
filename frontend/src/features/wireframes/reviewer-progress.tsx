'use client';

import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Film,
  Inbox,
  Plus,
  UploadCloud,
} from 'lucide-react';
import { type KeyboardEvent, useEffect, useRef } from 'react';

import {
  getProgressOverview,
  type ProgressInquiry,
  type ProgressTab,
  type ProgressVideo,
} from '@/features/wireframes/reviewer-progress-state';
import styles from '@/features/wireframes/reviewer-progress.module.css';

interface ReviewerProgressProps {
  showCompleted?: boolean;
  activeTab: ProgressTab;
  inquiries: ProgressInquiry[];
  videos: ProgressVideo[];
  isNavigating: boolean;
  onTabChange: (tab: ProgressTab) => void;
  onInquirySelect: (id: string) => void;
  onVideoSelect: (id: string) => void;
  onRegistrationOpen: () => void;
  onBack: () => void;
}

const videoStatusLabels = {
  queued: '등록 대기',
  running: '진행 중',
  failed: '확인 필요',
  succeeded: '완료',
};

export function ReviewerProgress({
  showCompleted = false,
  activeTab,
  inquiries,
  videos,
  isNavigating,
  onTabChange,
  onInquirySelect,
  onVideoSelect,
  onRegistrationOpen,
  onBack,
}: ReviewerProgressProps) {
  const overview = getProgressOverview(inquiries, videos);
  const isCompleted = showCompleted && activeTab === 'completed';
  const videoTab = isCompleted ? 'completed' : 'uploads';
  const visibleVideos = isCompleted ? overview.videos.completedItems : overview.videos.active;
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, []);
  const tabs = [
    {
      id: 'inquiries',
      label: '문의 처리 중',
      count: overview.inquiries.active.length,
      icon: Inbox,
    },
    {
      id: 'uploads',
      label: '영상 등록 중',
      count: overview.videos.active.length,
      icon: UploadCloud,
    },
    ...(showCompleted
      ? [
          {
            id: 'completed' as const,
            label: '등록 완료',
            count: overview.videos.completed,
            icon: CheckCircle2,
          },
        ]
      : []),
  ] as const;

  function handleTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (isNavigating) return;
    let next: number;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    tabRefs.current[next]?.focus();
    onTabChange(tabs[next].id);
  }

  return (
    <div className={styles.page}>
      <button className={styles.backButton} disabled={isNavigating} onClick={onBack} type="button">
        <ArrowLeft aria-hidden="true" /> 문의 목록으로
      </button>
      <header className={styles.heading}>
        <div>
          <p>WORK IN PROGRESS</p>
          <h1 ref={headingRef} tabIndex={-1}>
            영상 처리 현황
          </h1>
          <span>문의 처리부터 영상 등록까지, 진행 상황을 한눈에 확인하세요.</span>
        </div>
        <button
          className={styles.primaryButton}
          disabled={isNavigating}
          onClick={onRegistrationOpen}
          type="button"
        >
          <Plus aria-hidden="true" /> 영상 등록
        </button>
      </header>
      <div className={styles.summary}>
        <section aria-label="문의 처리 요약">
          <div className={styles.summaryTitle}>
            <Inbox aria-hidden="true" />
            <h2>문의 처리</h2>
            <span>전체 {overview.inquiries.total}개</span>
          </div>
          <p>
            <strong>
              {overview.inquiries.completed}
              <small> / {overview.inquiries.total}</small>
            </strong>{' '}
            완료
          </p>
          <progress
            aria-label="전체 문의 완료 현황"
            max={overview.inquiries.total || 1}
            value={overview.inquiries.completed}
          />
          <div className={styles.summaryMeta}>
            <span>
              처리 중 <b>{overview.inquiries.active.length}</b>
            </span>
            <span>
              대기 <b>{overview.inquiries.pending}</b>
            </span>
            <span>
              완료 <b>{overview.inquiries.completed}</b>
            </span>
          </div>
        </section>
        <section aria-label="영상 등록 요약">
          <div className={styles.summaryTitle}>
            <UploadCloud aria-hidden="true" />
            <h2>영상 등록</h2>
            <span>전체 {overview.videos.total}개</span>
          </div>
          <p>
            <strong>
              {overview.videos.completed}
              <small> / {overview.videos.total}</small>
            </strong>{' '}
            완료
          </p>
          <progress
            aria-label="전체 영상 등록 완료 현황"
            max={overview.videos.total || 1}
            value={overview.videos.completed}
          />
          <div className={styles.summaryMeta}>
            <span>
              진행 중 <b>{overview.videos.running}</b>
            </span>
            <span>
              대기 <b>{overview.videos.queued}</b>
            </span>
            <span>
              확인 필요 <b>{overview.videos.failed}</b>
            </span>
          </div>
        </section>
      </div>
      <section className={styles.panel} aria-busy={isNavigating}>
        <div className={styles.tabs} role="tablist" aria-label="처리 현황 선택">
          {tabs.map(({ id, label, count, icon: Icon }, index) => (
            <button
              aria-controls={`${id}-progress-panel`}
              aria-selected={activeTab === id}
              aria-disabled={isNavigating}
              id={`${id}-progress-tab`}
              key={id}
              onClick={() => {
                if (!isNavigating) onTabChange(id);
              }}
              onKeyDown={(event) => handleTabKeyDown(event, index)}
              ref={(element) => {
                tabRefs.current[index] = element;
              }}
              role="tab"
              tabIndex={activeTab === id ? 0 : -1}
              type="button"
            >
              <Icon aria-hidden="true" />
              {label}
              <span>{count}</span>
            </button>
          ))}
        </div>
        <div
          aria-labelledby="inquiries-progress-tab"
          hidden={activeTab !== 'inquiries'}
          id="inquiries-progress-panel"
          role="tabpanel"
          tabIndex={0}
        >
          <div className={styles.listHeading}>
            <h2>처리 중인 문의 목록</h2>
            <span>{overview.inquiries.active.length}개의 문의를 처리하고 있어요.</span>
          </div>
          <ul className={styles.list} aria-label="처리 중인 문의 목록">
            {overview.inquiries.active.map((inquiry) => (
              <li key={inquiry.id}>
                <button
                  className={styles.inquiryRow}
                  disabled={isNavigating}
                  onClick={() => onInquirySelect(inquiry.id)}
                  type="button"
                >
                  <span className={styles.thumbnail} data-image={inquiry.thumbnail}>
                    <Film aria-hidden="true" />
                  </span>
                  <span className={styles.rowCopy}>
                    <span className={styles.rowMeta}>
                      {inquiry.requester} · {inquiry.topic}
                      <em className={styles.chip} data-status="running">
                        처리 중
                      </em>
                    </span>
                    <strong>{inquiry.sceneTitle}</strong>
                    <span className={styles.description}>{inquiry.comment}</span>
                    <span className={styles.inquiryStage}>
                      <Clock3 aria-hidden="true" />
                      {inquiry.stage}
                    </span>
                  </span>
                  <span className={styles.rowEnd}>
                    {inquiry.daysAgo === 0 ? '오늘' : `${inquiry.daysAgo}일 전`}
                    <ArrowRight aria-hidden="true" />
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {!overview.inquiries.active.length ? (
            <div className={styles.empty}>
              <CheckCircle2 aria-hidden="true" />
              <h3>처리 중인 문의가 없어요</h3>
              <p>문의 목록에서 대기 중인 문의를 확인해 주세요.</p>
              <button
                className={styles.backButton}
                disabled={isNavigating}
                onClick={onBack}
                type="button"
              >
                문의 목록 보기 <ArrowRight aria-hidden="true" />
              </button>
            </div>
          ) : null}
        </div>
        <div
          aria-labelledby={`${videoTab}-progress-tab`}
          hidden={activeTab === 'inquiries'}
          id={`${videoTab}-progress-panel`}
          role="tabpanel"
          tabIndex={0}
        >
          <div className={styles.listHeading}>
            <h2>{isCompleted ? '등록 완료 영상' : '등록 중인 영상 목록'}</h2>
            <span>
              {isCompleted ? '검색할 수 있는 영상' : '대기·진행 중·확인이 필요한 영상'}{' '}
              {visibleVideos.length}개
            </span>
          </div>
          <ul
            className={styles.list}
            aria-label={isCompleted ? '등록 완료 영상 목록' : '등록 중인 영상 목록'}
          >
            {visibleVideos.map((video) => (
              <li className={styles.videoRow} key={video.id}>
                <span className={styles.videoIcon}>
                  <Film aria-hidden="true" />
                </span>
                <div className={styles.videoCopy}>
                  <div className={styles.videoTitle}>
                    <strong>{video.title}</strong>
                    <span className={styles.chip} data-status={video.status}>
                      {videoStatusLabels[video.status]}
                    </span>
                  </div>
                  <p className={styles.fileName}>{video.fileName}</p>
                  <div className={styles.stageCaption}>
                    <span>{video.stage}</span>
                    <span>
                      {video.completedSteps} / {video.totalSteps}단계 완료
                    </span>
                  </div>
                  <progress
                    aria-label={`${video.title} 완료 단계`}
                    max={video.totalSteps}
                    value={video.completedSteps}
                    data-status={video.status}
                  />
                  <p className={styles.videoDescription}>{video.description}</p>
                </div>
                {video.canOpen ? (
                  <button
                    aria-label={`${video.title} 상세 보기`}
                    className={styles.detailButton}
                    disabled={isNavigating}
                    onClick={() => onVideoSelect(video.id)}
                    type="button"
                  >
                    상세 보기 <ArrowRight aria-hidden="true" />
                  </button>
                ) : (
                  <span className={styles.demo}>데모</span>
                )}
              </li>
            ))}
          </ul>
          {!visibleVideos.length ? (
            <div className={styles.empty}>
              <CheckCircle2 aria-hidden="true" />
              <h3>
                {isCompleted ? '아직 등록이 완료된 영상이 없어요' : '등록 중인 영상이 없어요'}
              </h3>
              <p>
                {isCompleted
                  ? '영상 처리와 검색 반영이 끝나면 이곳에서 확인할 수 있어요.'
                  : '새 영상을 등록하면 이곳에서 진행 상황을 확인할 수 있어요.'}
              </p>
            </div>
          ) : null}
        </div>
      </section>
      <p className={styles.footnote}>
        현재 예시 데이터와 이 화면에서 등록한 데모 영상의 진행 상태입니다.
      </p>
    </div>
  );
}
