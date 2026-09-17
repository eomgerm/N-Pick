'use client';

import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  Film,
  Flag,
  LoaderCircle,
  Play,
  X,
} from 'lucide-react';
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  inquiryResolutionLabels,
  inquiryStatusLabels,
  type InquiryResolution,
} from '@/features/wireframes/inquiry-state';
import {
  formatTimestamp,
  getVerificationStatusLabel,
  type SearchResult,
} from '@/features/wireframes/demo-scenes';
import {
  canCreateInquiry,
  type SearchExecutionPresentation,
  successfulSearchExecution,
} from '@/features/wireframes/search-execution-status';
import {
  SearchResultNotices,
  SearchResultSafetyNotice,
} from '@/features/wireframes/search-result-notices';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import shinhanStyles from '@/features/wireframes/shinhan-search.module.css';
import { ScenePreviewPlayer } from '@/features/wireframes/scene-preview-player';

interface SceneDialogProps {
  children: ReactNode;
  className: string;
  describedBy?: string;
  labelledBy: string;
  theme: WireframeTheme;
  isLocked?: boolean;
  onClose: () => void;
}

export function SceneDialog({
  children,
  className,
  describedBy,
  labelledBy,
  theme,
  isLocked = false,
  onClose,
}: SceneDialogProps) {
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
      aria-describedby={describedBy}
      aria-labelledby={labelledBy}
      className={`${styles.dialogTheme} ${styles.dialog} ${className}`}
      data-theme={theme}
      onCancel={(event) => {
        event.preventDefault();
        if (!isLocked) onClose();
      }}
      onMouseDown={(event) => {
        if (isLocked || event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (
          event.clientX < bounds.left ||
          event.clientX > bounds.right ||
          event.clientY < bounds.top ||
          event.clientY > bounds.bottom
        ) {
          event.preventDefault();
          onClose();
        }
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
      | 'additionalEvidence'
      | 'clipId'
      | 'clip'
      | 'searchResultId'
      | 'totalSeconds'
      | 'totalDuration'
      | 'broadcastDate'
      | 'filmedDate'
      | 'filmingState'
      | 'matchEvidence'
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
  isSubmitting?: boolean;
  onInquiry?: () => void;
  contextLabel?: string;
  notice?: string;
  autoPlay?: boolean;
  searchExecution?: SearchExecutionPresentation;
  onClose: () => void;
}

export function ScenePreviewDialog({
  result: initialResult,
  scenes,
  theme,
  isSubmitted,
  isSubmitting = false,
  onInquiry,
  onClose,
  contextLabel,
  notice,
  autoPlay = true,
  searchExecution = successfulSearchExecution,
}: ScenePreviewDialogProps) {
  const [selectedSceneId, setSelectedSceneId] = useState(initialResult.id);
  const result = scenes?.find((scene) => scene.id === selectedSceneId) ?? initialResult;
  const filmingStatus = result.filmedDate ? (result.filmingState ?? 'unknown') : 'unknown';
  const evidenceField = result.matchEvidence?.field ?? result.evidenceType;
  const evidenceValue = result.matchEvidence?.value ?? result.evidence;
  const evidenceSource = result.matchEvidence?.source ?? result.source;
  const selectedSceneIndex = scenes?.findIndex((scene) => scene.id === result.id) ?? -1;
  const sceneListRef = useRef<HTMLOListElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const inquiryUnavailableReasonId = useId();
  const hasSavedResult =
    typeof result.searchResultId === 'string' && /^[1-9]\d*$/.test(result.searchResultId);
  const isInquiryUnavailable =
    !isSubmitted && (!hasSavedResult || !canCreateInquiry(searchExecution));
  useEffect(() => {
    closeButtonRef.current?.focus({ preventScroll: true });
  }, []);
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
  function handleSceneSelect(scene: ScenePreviewResult) {
    if (scene.id === result.id) return;
    setSelectedSceneId(scene.id);
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
          <div className={styles.previewTitleRow}>
            <h2 id="preview-title">{result.title}</h2>
            {result.clip ? (
              <p className={styles.previewClipName} title={result.clip}>
                {result.clip}
              </p>
            ) : null}
          </div>
          {notice ? <p className={styles.previewNotice}>{notice}</p> : null}
        </div>
        <div className={styles.previewHeaderActions}>
          {onInquiry ? (
            <div className={styles.previewInquiryAction}>
              <button
                aria-busy={isSubmitting}
                aria-describedby={isInquiryUnavailable ? inquiryUnavailableReasonId : undefined}
                aria-disabled={isInquiryUnavailable || undefined}
                className={styles.previewReportButton}
                data-state={
                  isSubmitted
                    ? 'submitted'
                    : isSubmitting
                      ? 'submitting'
                      : isInquiryUnavailable
                        ? 'unavailable'
                        : 'ready'
                }
                disabled={isSubmitted || isSubmitting}
                onClick={() => {
                  if (!isInquiryUnavailable) onInquiry();
                }}
                type="button"
              >
                {isSubmitted ? (
                  <Check aria-hidden="true" />
                ) : isSubmitting ? (
                  <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
                ) : isInquiryUnavailable ? (
                  <AlertTriangle aria-hidden="true" />
                ) : (
                  <Flag aria-hidden="true" />
                )}
                {isSubmitted
                  ? '접수됨'
                  : isSubmitting
                    ? '접수 중'
                    : isInquiryUnavailable
                      ? '문의 불가'
                      : '이상해요'}
              </button>
              {isInquiryUnavailable ? (
                <span
                  className={styles.previewInquiryTooltip}
                  id={inquiryUnavailableReasonId}
                  role="tooltip"
                >
                  {!canCreateInquiry(searchExecution)
                    ? '검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'
                    : '저장된 검색 결과가 아니므로 문의할 수 없습니다.'}
                </span>
              ) : null}
            </div>
          ) : null}
          <button
            aria-label="Preview 닫기"
            className={styles.iconButton}
            onClick={onClose}
            ref={closeButtonRef}
            title="Preview 닫기"
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </div>
      </div>

      <div className={styles.previewModalBody}>
        <div className={styles.previewPlayer}>
          <ScenePreviewPlayer
            key={`${result.id}:${result.clipId ?? ''}`}
            clipId={result.clipId}
            sceneStart={result.sceneStart}
            sceneEnd={result.sceneEnd}
            title={result.title}
            autoPlay={autoPlay}
          />
          <SearchResultNotices
            execution={searchExecution}
            variant="preview"
            showSafetyNotice={false}
          />
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
          <section className={styles.previewDetails} aria-labelledby="preview-details-title">
            <h3 id="preview-details-title">장면 정보</h3>
            <dl>
              <div>
                <dt>방송일</dt>
                <dd>{result.broadcastDate ?? '미상'}</dd>
              </div>
              <div>
                <dt>촬영일</dt>
                <dd>
                  {result.filmedDate
                    ? `${result.filmedDate} · ${getVerificationStatusLabel(filmingStatus)}`
                    : getVerificationStatusLabel(filmingStatus)}
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
            </dl>
          </section>
          <section
            className={styles.previewEvidenceSection}
            aria-labelledby="preview-evidence-title"
          >
            <h3 id="preview-evidence-title">{onInquiry ? '검색 근거' : '확인 근거'}</h3>
            <div className={styles.previewEvidence}>
              <div className={styles.previewEvidenceHeading}>
                <span>
                  {evidenceField}
                  {onInquiry ? ' 일치' : ''}
                </span>
                {result.matchEvidence ? (
                  <span className={styles.statusBadge} data-status={result.matchEvidence.status}>
                    {getVerificationStatusLabel(result.matchEvidence.status)}
                  </span>
                ) : null}
              </div>
              <strong>{evidenceValue}</strong>
              <p>{result.matchEvidence ? `출처 · ${evidenceSource}` : evidenceSource}</p>
            </div>
            {result.additionalEvidence?.map((evidence, index) => (
              <div className={styles.previewEvidence} key={index}>
                <div className={styles.previewEvidenceHeading}>
                  <span>{evidence.field}</span>
                  <span className={styles.statusBadge} data-status={evidence.status}>
                    {getVerificationStatusLabel(evidence.status)}
                  </span>
                </div>
                <strong>{evidence.value}</strong>
                <p>출처 · {evidence.source}</p>
              </div>
            ))}
          </section>
          <SearchResultSafetyNotice variant="preview" />
        </div>
      </div>
    </SceneDialog>
  );
}

export type InquiryDetails = {
  comment: string;
  snapshotUnavailable?: boolean;
} & (
  | { status: 'open' | 'reviewing' }
  | { status: 'closed'; resolution: InquiryResolution | null; resolutionSummary: string | null }
);

type InquiryDialogProps = {
  result: Pick<SearchResult, 'title' | 'time'> & { id: string | number; evidenceType?: string };
  theme: WireframeTheme;
  query: string;
  error?: unknown;
  isSubmitting?: boolean;
  onCommentChange?: () => void;
  onClose: () => void;
} & (
  | { history: InquiryDetails; onSubmit?: never }
  | { history?: never; onSubmit: (comment: string) => void | Promise<void> }
);

export function InquiryDialog({
  result,
  theme,
  query,
  history,
  error,
  isSubmitting = false,
  onCommentChange,
  onSubmit,
  onClose,
}: InquiryDialogProps) {
  const [comment, setComment] = useState(history?.comment ?? '');
  const errorId = useId();
  const hasError = error !== undefined && error !== null;

  if (history) {
    const statusDescription = {
      open: '문의가 접수되었습니다. 검수자의 확인을 기다리고 있습니다.',
      reviewing: '검수자가 문의 내용과 검색 결과를 확인하고 있습니다.',
      closed: null,
    }[history.status];

    return (
      <SceneDialog
        theme={theme}
        className={styles.inquiryDetailModal}
        labelledBy="inquiry-title"
        describedBy={statusDescription ? 'inquiry-status-message' : undefined}
        onClose={onClose}
      >
        <header className={styles.inquiryDetailHeader}>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-3">
              <h2 id="inquiry-title">문의 상세</h2>
              <span className={styles.inquiryDetailStatus} data-status={history.status}>
                <span aria-hidden="true" />
                {inquiryStatusLabels[history.status]}
              </span>
            </div>
            {statusDescription ? <p id="inquiry-status-message">{statusDescription}</p> : null}
          </div>
          <button
            aria-label="문의 창 닫기"
            className={styles.inquiryDetailClose}
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" size={20} />
          </button>
        </header>

        <div className={styles.inquiryDetailBody}>
          <section aria-labelledby="inquiry-context-title">
            <h3 className={styles.inquiryDetailLabel} id="inquiry-context-title">
              당시 검색어
            </h3>
            <p className={styles.inquiryDetailQuery}>{query}</p>
            <div className={styles.inquiryDetailScene}>
              <span className={styles.inquiryDetailSceneIcon} aria-hidden="true">
                <Film size={22} strokeWidth={1.5} />
              </span>
              <div className="min-w-0">
                <p className={styles.inquiryDetailSceneLabel}>문의 장면 · #{result.id}</p>
                <p className={styles.inquiryDetailSceneTitle}>{result.title}</p>
                <p className={styles.inquiryDetailSceneMeta}>
                  <span>{result.time}</span>
                  {result.evidenceType ? <span>{result.evidenceType} 일치</span> : null}
                </p>
              </div>
            </div>
            {history.snapshotUnavailable ? (
              <p className="mt-3 text-sm text-(--muted)">
                당시 검색 결과의 상세 근거 기록은 제공되지 않습니다.
              </p>
            ) : null}
          </section>

          <section aria-labelledby="inquiry-comment-title">
            <h3 className={styles.inquiryDetailLabel} id="inquiry-comment-title">
              문의 내용
            </h3>
            <p className={styles.inquiryDetailComment} data-empty={!history.comment.trim()}>
              {history.comment.trim() ? history.comment : '추가 설명 없이 접수된 문의입니다.'}
            </p>
          </section>

          {history.status === 'closed' ? (
            <section
              aria-labelledby="inquiry-resolution-title"
              className={styles.inquiryDetailResolution}
            >
              <h3 className={styles.inquiryDetailLabel} id="inquiry-resolution-title">
                처리 결과
              </h3>
              <p className={styles.inquiryDetailResolutionTitle}>
                {history.resolution
                  ? inquiryResolutionLabels[history.resolution]
                  : '처리 결과 기록 없음'}
              </p>
              <p className={styles.inquiryDetailResolutionSummary}>
                {history.resolutionSummary || '처리 사유가 기록되지 않았습니다.'}
              </p>
            </section>
          ) : null}
        </div>
      </SceneDialog>
    );
  }

  const statusMessage =
    '접수 후 검수자가 확인합니다. 현재 검색 결과나 다른 검색은 즉시 변경되지 않습니다.';

  return (
    <SceneDialog
      theme={theme}
      className={styles.modal}
      labelledBy="inquiry-title"
      isLocked={isSubmitting}
      onClose={onClose}
    >
      <div className={styles.modalHeader}>
        <div>
          <span>결과 문의</span>
          <div className="mt-1 flex flex-wrap items-center gap-3">
            <h2 id="inquiry-title">이 장면에 이상이 있나요?</h2>
          </div>
        </div>
        <button
          aria-label="문의 창 닫기"
          className={styles.iconButton}
          disabled={isSubmitting}
          onClick={onClose}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </div>
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
        aria-busy={isSubmitting}
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit(comment);
        }}
      >
        <label htmlFor="inquiry-comment">설명 (선택)</label>
        <textarea
          aria-describedby={
            [statusMessage ? 'inquiry-status-message' : null, hasError ? errorId : null]
              .filter(Boolean)
              .join(' ') || undefined
          }
          disabled={isSubmitting}
          id="inquiry-comment"
          onChange={(event) => {
            setComment(event.target.value);
            onCommentChange?.();
          }}
          placeholder="무엇이 이상했는지 알려주세요. 비워두어도 접수할 수 있어요."
          rows={4}
          value={comment}
        />
        {statusMessage ? <p id="inquiry-status-message">{statusMessage}</p> : null}
        {hasError ? (
          <div className={styles.inquiryError}>
            <ApiErrorNotice error={error} id={errorId} />
          </div>
        ) : null}
        <div className={styles.modalActions}>
          <button disabled={isSubmitting} onClick={onClose} type="button">
            취소
          </button>
          <button className={styles.submitInquiry} disabled={isSubmitting} type="submit">
            {isSubmitting ? (
              <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
            ) : (
              <Flag aria-hidden="true" />
            )}
            {isSubmitting ? '접수 중' : hasError ? '다시 시도' : '문의 접수'}
          </button>
        </div>
      </form>
    </SceneDialog>
  );
}
