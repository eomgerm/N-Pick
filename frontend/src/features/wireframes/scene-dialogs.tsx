'use client';

import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Flag,
  LoaderCircle,
  Pause,
  Play,
  RotateCcw,
  X,
} from 'lucide-react';
import { type CSSProperties, type ReactNode, useEffect, useRef, useState } from 'react';

import { formatTimestamp, type SearchResult } from '@/features/wireframes/demo-scenes';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import shinhanStyles from '@/features/wireframes/shinhan-search.module.css';

interface SceneDialogProps {
  children: ReactNode;
  className: string;
  labelledBy: string;
  theme: WireframeTheme;
  onClose: () => void;
}

function SceneDialog({ children, className, labelledBy, theme, onClose }: SceneDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    dialog?.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      dialog?.close();
      document.body.style.overflow = previousOverflow;
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) {
        previousFocus.focus({ preventScroll: true });
      }
    };
  }, []);

  return (
    <dialog
      aria-labelledby={labelledBy}
      className={`${styles.dialogTheme} ${styles.dialog} ${className}`}
      data-theme={theme}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onMouseDown={(event) => {
        if (event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (
          event.clientX < bounds.left ||
          event.clientX > bounds.right ||
          event.clientY < bounds.top ||
          event.clientY > bounds.bottom
        )
          onClose();
      }}
      ref={dialogRef}
    >
      {children}
    </dialog>
  );
}

type ScenePreviewResult = Pick<
  SearchResult,
  | 'title'
  | 'duration'
  | 'sceneStart'
  | 'sceneEnd'
  | 'evidence'
  | 'source'
  | 'imageClass'
  | 'imageLabel'
> &
  Partial<
    Pick<
      SearchResult,
      | 'clip'
      | 'totalSeconds'
      | 'totalDuration'
      | 'broadcastDate'
      | 'filmedDate'
      | 'filmingState'
      | 'shotType'
      | 'sceneType'
    >
  > & {
    id: string | number;
    evidenceType: string;
  };

interface ScenePreviewDialogProps {
  result: ScenePreviewResult;
  scenes?: ScenePreviewResult[];
  theme: WireframeTheme;
  isSubmitted?: boolean;
  onInquiry?: () => void;
  contextLabel?: string;
  notice?: string;
  autoPlay?: boolean;
  onClose: () => void;
  keepLoading?: boolean;
}

export function ScenePreviewDialog({
  result: initialResult,
  scenes,
  theme,
  isSubmitted,
  onInquiry,
  onClose,
  keepLoading = false,
  contextLabel,
  notice,
  autoPlay = false,
}: ScenePreviewDialogProps) {
  const [selectedSceneId, setSelectedSceneId] = useState(initialResult.id);
  const result = scenes?.find((scene) => scene.id === selectedSceneId) ?? initialResult;
  const selectedSceneIndex = scenes?.findIndex((scene) => scene.id === result.id) ?? -1;
  const [loadedSceneId, setLoadedSceneId] = useState<string | number | null>(null);
  const isLoading = keepLoading || loadedSceneId !== result.id;
  const [isPlaying, setIsPlaying] = useState(autoPlay);
  const [replayCount, setReplayCount] = useState(0);
  const sceneListRef = useRef<HTMLOListElement>(null);
  useEffect(() => {
    if (keepLoading) return;
    // 미디어 API 연결 전, 로딩 → 준비 화면 전환을 보여 주는 데모입니다.
    const timer = window.setTimeout(() => setLoadedSceneId(result.id), 1000);
    return () => window.clearTimeout(timer);
  }, [keepLoading, result.id]);
  useEffect(() => {
    const list = sceneListRef.current;
    const selected = list?.querySelector<HTMLElement>('[aria-current="true"]');
    if (!list || !selected) return;
    const listBounds = list.getBoundingClientRect();
    const selectedBounds = selected.getBoundingClientRect();
    if (selectedBounds.top < listBounds.top) {
      list.scrollTop += selectedBounds.top - listBounds.top;
    } else if (selectedBounds.bottom > listBounds.bottom) {
      list.scrollTop += selectedBounds.bottom - listBounds.bottom;
    }
  }, [result.id]);
  const previewTimelineStyle = {
    '--scene-start': result.totalSeconds
      ? `${(result.sceneStart / result.totalSeconds) * 100}%`
      : '0%',
    '--scene-width': result.totalSeconds
      ? `${((result.sceneEnd - result.sceneStart) / result.totalSeconds) * 100}%`
      : '100%',
  } as CSSProperties;

  function handleReplay() {
    setReplayCount((current) => current + 1);
    setIsPlaying(true);
  }

  function handleSceneSelect(scene: ScenePreviewResult) {
    if (scene.id === result.id) return;
    setSelectedSceneId(scene.id);
    setLoadedSceneId(null);
    setIsPlaying(autoPlay);
    setReplayCount(0);
  }

  return (
    <SceneDialog
      theme={theme}
      className={styles.previewModal}
      labelledBy="preview-title"
      onClose={onClose}
    >
      <div className={styles.previewModalHeader}>
        <div>
          <span>{contextLabel ?? `구간 영상 정보 · 장면 #${result.id}`}</span>
          <h2 id="preview-title">{result.title}</h2>
          {result.clip ? <p>{result.clip}</p> : null}
          {notice ? <p className={styles.previewNotice}>{notice}</p> : null}
        </div>
        <div className={styles.previewHeaderActions}>
          {onInquiry ? (
            <button
              className={styles.previewReportButton}
              disabled={isSubmitted}
              onClick={onInquiry}
              type="button"
            >
              {isSubmitted ? <Check aria-hidden="true" /> : <Flag aria-hidden="true" />}
              {isSubmitted ? '접수됨' : '이상해요'}
            </button>
          ) : null}
          <button
            aria-label="Preview 닫기"
            className={styles.iconButton}
            onClick={onClose}
            title="Preview 닫기"
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </div>
      </div>

      <div className={styles.previewModalBody}>
        <div className={styles.previewPlayer}>
          {isLoading ? (
            <div aria-busy="true" className={shinhanStyles.previewLoading} role="status">
              <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
              <strong>장면을 불러오고 있어요</strong>
              <p>
                {formatTimestamp(result.sceneStart)}부터 시작하는 영상을 준비하고 있어요.
                <br />
                잠시만 기다려 주세요.
              </p>
            </div>
          ) : (
            <div
              aria-label={result.imageLabel}
              className={`${styles.previewMedia} ${result.imageClass}`}
              key={`media-${result.id}-${replayCount}`}
              role="group"
            >
              <button
                aria-label={isPlaying ? '일시정지' : '재생'}
                className={styles.previewPlay}
                onClick={() => setIsPlaying((current) => !current)}
                title={isPlaying ? '일시정지' : '재생'}
                type="button"
              >
                {isPlaying ? (
                  <Pause aria-hidden="true" fill="currentColor" />
                ) : (
                  <Play aria-hidden="true" fill="currentColor" />
                )}
              </button>
              <span className={styles.playingBadge} data-playing={isPlaying}>
                <span aria-hidden="true" /> {isPlaying ? '재생 중' : '일시 정지'}
              </span>
            </div>
          )}
          {!notice ? (
            <p className={shinhanStyles.previewNote}>
              화면 미리보기용 영상 상태입니다. 실제 영상 재생은 연결 전이에요.
            </p>
          ) : null}

          <div
            aria-label={`${result.totalDuration ? `전체 ${result.totalDuration} 중 ` : ''}${formatTimestamp(
              result.sceneStart,
            )}부터 ${formatTimestamp(result.sceneEnd)}까지 재생 구간`}
            className={`${styles.timelinePanel} ${isPlaying && !isLoading ? styles.timelinePlaying : ''}`}
            key={`timeline-${result.id}-${replayCount}`}
            style={previewTimelineStyle}
          >
            <div className={styles.timelineHeading}>
              <strong>{result.totalSeconds ? '전체 영상' : '문의한 영상 구간'}</strong>
              <span>
                선택 구간 <b>{result.duration}</b>
              </span>
            </div>
            {result.totalSeconds ? (
              <div className={styles.timelineTrack}>
                <span className={styles.sceneRange}>
                  <span className={styles.sceneProgress} />
                  <span className={styles.timelinePlayhead} />
                </span>
              </div>
            ) : null}
            {result.totalDuration ? (
              <div className={styles.timelineLabels}>
                <span>00:00</span>
                <span>{result.totalDuration}</span>
              </div>
            ) : null}
            <div className={styles.sceneBounds}>
              <span>IN {formatTimestamp(result.sceneStart)}</span>
              <span>OUT {formatTimestamp(result.sceneEnd)}</span>
            </div>
          </div>

          <div className={`${styles.previewControls} ${shinhanStyles.playbackControls}`}>
            <div className={styles.previewControlButtons}>
              <button
                className={styles.playbackButton}
                disabled={isLoading}
                onClick={() => setIsPlaying((current) => !current)}
                type="button"
              >
                {isPlaying ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
                {isPlaying ? '일시정지' : '재생'}
              </button>
              <button disabled={isLoading} onClick={handleReplay} type="button">
                <RotateCcw aria-hidden="true" /> 구간 다시 재생
              </button>
            </div>
            {result.totalDuration ? (
              <span>
                <Clock3 aria-hidden="true" /> 원본 {result.totalDuration}
              </span>
            ) : null}
          </div>
        </div>

        <div className={styles.previewSidebar}>
          {scenes && scenes.length > 0 ? (
            <section className={styles.sceneBrowser} aria-labelledby="all-scenes-title">
              <div className={styles.sceneBrowserHeading}>
                <h3 id="all-scenes-title">
                  전체 구간 <span>{scenes.length}</span>
                </h3>
                <span>시간순</span>
              </div>
              <div className={styles.sceneNavigation}>
                <button
                  aria-label="이전 구간"
                  disabled={selectedSceneIndex <= 0}
                  onClick={() => handleSceneSelect(scenes[selectedSceneIndex - 1])}
                  type="button"
                >
                  <ChevronLeft aria-hidden="true" /> 이전
                </button>
                <span aria-live="polite" aria-atomic="true">
                  구간 {selectedSceneIndex + 1} / {scenes.length}
                </span>
                <button
                  aria-label="다음 구간"
                  disabled={selectedSceneIndex >= scenes.length - 1}
                  onClick={() => handleSceneSelect(scenes[selectedSceneIndex + 1])}
                  type="button"
                >
                  다음 <ChevronRight aria-hidden="true" />
                </button>
              </div>
              <ol className={styles.sceneList} ref={sceneListRef}>
                {scenes.map((scene, index) => (
                  <li key={scene.id}>
                    <button
                      aria-current={scene.id === result.id ? 'true' : undefined}
                      aria-label={`구간 ${index + 1}: ${scene.title}, ${formatTimestamp(scene.sceneStart)}부터 ${formatTimestamp(scene.sceneEnd)}까지`}
                      onClick={() => handleSceneSelect(scene)}
                      type="button"
                    >
                      <span className={styles.sceneNumber}>
                        {String(index + 1).padStart(2, '0')}
                      </span>
                      <span className={styles.sceneListInfo}>
                        <strong>{scene.title}</strong>
                        <span>
                          {formatTimestamp(scene.sceneStart)} – {formatTimestamp(scene.sceneEnd)} ·{' '}
                          {scene.duration}
                        </span>
                      </span>
                      {scene.id === result.id ? <Play aria-hidden="true" /> : null}
                    </button>
                  </li>
                ))}
              </ol>
            </section>
          ) : null}
          <div className={styles.previewDetails}>
            <p>장면 정보</p>
            <dl>
              <div>
                <dt>방송일</dt>
                <dd>{result.broadcastDate ?? '미상'}</dd>
              </div>
              <div>
                <dt>촬영일</dt>
                <dd>
                  {result.filmedDate ? (
                    <>
                      {result.filmedDate} ·{' '}
                      {result.filmingState === 'verified'
                        ? '검증됨'
                        : result.filmingState === 'unknown'
                          ? '정보 없음'
                          : '미검증'}
                    </>
                  ) : (
                    '미상'
                  )}
                </dd>
              </div>
              <div>
                <dt>샷 유형</dt>
                <dd>{result.shotType ?? '정보 없음'}</dd>
              </div>
              <div>
                <dt>장면 유형</dt>
                <dd>{result.sceneType ?? '정보 없음'}</dd>
              </div>
              <div>
                <dt>근거</dt>
                <dd>{result.evidenceType}</dd>
              </div>
            </dl>
            <div className={styles.previewEvidence}>
              <span>
                {result.evidenceType}
                {onInquiry ? ' 일치' : ''}
              </span>
              <strong>{result.evidence}</strong>
              <p>{result.source}</p>
            </div>
          </div>
          <div className={styles.safetyNotice}>
            <AlertTriangle aria-hidden="true" />
            <p>
              <strong>송출 전 최종 확인</strong>
              내용·최신성·권리·사용 적합성을 확인하세요.
            </p>
          </div>
        </div>
      </div>
    </SceneDialog>
  );
}

export type InquiryDetails = {
  comment: string;
} & ({ status: 'pending' | 'reviewing' } | { status: 'resolved'; resolution: string });

const inquiryStatusLabels = {
  pending: '대기',
  reviewing: '처리 중',
  resolved: '완료',
} as const;

interface InquiryDialogProps {
  result: SearchResult;
  theme: WireframeTheme;
  query: string;
  history?: InquiryDetails;
  onSubmit: (comment: string) => void;
  onClose: () => void;
}

export function InquiryDialog({
  result,
  theme,
  query,
  history,
  onSubmit,
  onClose,
}: InquiryDialogProps) {
  const [comment, setComment] = useState(history?.comment ?? '');
  const statusMessage = !history
    ? '접수 후 검수자가 확인합니다. 현재 검색 결과나 다른 검색은 즉시 변경되지 않습니다.'
    : history.status === 'pending'
      ? '접수되어 검수자 확인을 기다리고 있습니다.'
      : history.status === 'reviewing'
        ? '검수자가 처리 중인 문의입니다.'
        : '처리가 완료된 문의입니다. 문의 내용과 처리 내용을 확인하세요.';

  return (
    <SceneDialog
      theme={theme}
      className={styles.modal}
      labelledBy="inquiry-title"
      onClose={onClose}
    >
      <div className={styles.modalHeader}>
        <div>
          <span>{history ? '문의 기록' : '결과 문의'}</span>
          <h2 id="inquiry-title">{history ? '문의 상세' : '이 장면에 이상이 있나요?'}</h2>
        </div>
        <button
          aria-label="문의 창 닫기"
          className={styles.iconButton}
          onClick={onClose}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </div>
      {history ? (
        <span className={styles.inquiryStatus} data-status={history.status}>
          {inquiryStatusLabels[history.status]}
        </span>
      ) : null}
      <div className={styles.modalResult}>
        <span>#{result.id}</span>
        <div>
          <strong>{result.title}</strong>
          <p>
            {result.time} · {result.evidenceType} 일치
          </p>
        </div>
      </div>
      <dl className={styles.inquiryContext}>
        <dt>당시 검색어</dt>
        <dd>{query}</dd>
      </dl>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!history) onSubmit(comment);
        }}
      >
        <label htmlFor="inquiry-comment">{history ? '문의 내용' : '설명 (선택)'}</label>
        <textarea
          aria-describedby="inquiry-status-message"
          id="inquiry-comment"
          onChange={(event) => {
            if (!history) setComment(event.target.value);
          }}
          placeholder={
            !history
              ? '무엇이 이상했는지 알려주세요. 비워두어도 접수할 수 있어요.'
              : '작성한 설명이 없습니다.'
          }
          readOnly={Boolean(history)}
          rows={4}
          value={comment}
        />
        <p id="inquiry-status-message">{statusMessage}</p>
        {history?.status === 'resolved' ? (
          <section aria-labelledby="inquiry-resolution-title" className={styles.inquiryResolution}>
            <h3 id="inquiry-resolution-title">
              <Check aria-hidden="true" /> 처리 내용
            </h3>
            <p>{history.resolution}</p>
          </section>
        ) : null}
        <div className={styles.modalActions}>
          <button onClick={onClose} type="button">
            {history ? '닫기' : '취소'}
          </button>
          {!history ? (
            <button className={styles.submitInquiry} type="submit">
              <Flag aria-hidden="true" />
              문의 접수
            </button>
          ) : null}
        </div>
      </form>
    </SceneDialog>
  );
}
