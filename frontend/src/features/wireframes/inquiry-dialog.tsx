'use client';

import { Film, Flag, LoaderCircle, X } from 'lucide-react';
import { useId, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  inquiryResolutionLabels,
  inquiryStatusLabels,
  type InquiryResolution,
} from '@/features/wireframes/inquiry-state';
import { rejectOversizedPaste } from '@/features/wireframes/input-validation';
import { SceneDialog } from '@/features/wireframes/scene-dialog';
import type { SearchResult } from '@/features/wireframes/demo-scenes';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import shinhanStyles from '@/features/wireframes/shinhan-search.module.css';

export type InquiryDetails = {
  comment: string;
  snapshotUnavailable?: boolean;
} & (
  | { status: 'open' | 'reviewing' }
  | { status: 'closed'; resolution: InquiryResolution | null; resolutionSummary: string | null }
);

const INQUIRY_REASON_ETC = '기타';
const INQUIRY_REASONS = [
  '검색 내용과 맞지 않는 장면',
  '장면 설명이 정확하지 않음',
  INQUIRY_REASON_ETC,
];
const INQUIRY_ETC_MAX_LENGTH = 200;

/** 프리셋은 선택 사항이며, 기타일 때만 선택형 상세 설명을 받는다. */
function validateInquiry(reason: string, comment: string): string {
  if (reason === INQUIRY_REASON_ETC && comment.length > INQUIRY_ETC_MAX_LENGTH) {
    return `상세 설명은 ${INQUIRY_ETC_MAX_LENGTH}자 이내로 입력해 주세요.`;
  }
  return '';
}

export function buildInquiryComment(reason: string, comment: string): string {
  return reason === INQUIRY_REASON_ETC ? comment.trim() : reason;
}

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
  const [reason, setReason] = useState('');
  const [commentError, setCommentError] = useState('');
  const errorId = useId();
  const hasError = error !== undefined && error !== null;

  if (history) {
    const statusDescription = {
      open: '문의가 접수되었습니다. 아카이브 팀의 확인을 기다리고 있습니다.',
      reviewing: '아카이브 팀이 문의 내용과 검색 결과를 확인하고 있습니다.',
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
    '접수 후 아카이브 팀이 확인합니다. 현재 검색 결과나 다른 검색은 즉시 변경되지 않습니다.';

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
          const validationError = validateInquiry(reason, comment);
          setCommentError(validationError);
          if (validationError || isSubmitting) return;
          onSubmit(buildInquiryComment(reason, comment));
        }}
      >
        <fieldset className={styles.inquiryReasons}>
          <legend>어떤 점이 이상한가요? (선택)</legend>
          {INQUIRY_REASONS.map((option) => (
            <label key={option} className={styles.inquiryReason} data-active={reason === option}>
              <input
                type="radio"
                name="inquiry-reason"
                value={option}
                checked={reason === option}
                disabled={isSubmitting}
                onChange={() => {
                  setReason(option);
                  setCommentError('');
                  onCommentChange?.();
                }}
              />
              {option}
            </label>
          ))}
        </fieldset>
        {reason === INQUIRY_REASON_ETC ? (
          <>
            <label htmlFor="inquiry-comment">상세 설명 (선택)</label>
            <textarea
              aria-describedby={
                [commentError ? 'inquiry-comment-error' : null, hasError ? errorId : null]
                  .filter(Boolean)
                  .join(' ') || undefined
              }
              disabled={isSubmitting}
              id="inquiry-comment"
              maxLength={INQUIRY_ETC_MAX_LENGTH}
              aria-invalid={Boolean(commentError)}
              onChange={(event) => {
                setComment(event.target.value);
                setCommentError(validateInquiry(reason, event.target.value));
                onCommentChange?.();
              }}
              onPaste={(event) =>
                rejectOversizedPaste(event, INQUIRY_ETC_MAX_LENGTH, () =>
                  setCommentError(`상세 설명은 ${INQUIRY_ETC_MAX_LENGTH}자 이내로 입력해 주세요.`),
                )
              }
              placeholder="직접 설명하지 않아도 접수할 수 있어요."
              rows={3}
              value={comment}
            />
          </>
        ) : null}
        {commentError ? (
          <p id="inquiry-comment-error" role="alert" className="text-sm text-(--danger)">
            {commentError}
          </p>
        ) : null}
        <p id="inquiry-status-message">{statusMessage}</p>
        {hasError ? (
          <div className={styles.inquiryError}>
            <ApiErrorNotice error={error} id={errorId} />
          </div>
        ) : null}
        <div className={styles.modalActions}>
          <button disabled={isSubmitting} onClick={onClose} type="button">
            취소
          </button>
          <button
            className={styles.submitInquiry}
            disabled={isSubmitting || Boolean(validateInquiry(reason, comment))}
            type="submit"
          >
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
