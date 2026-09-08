'use client';

import { ArrowLeft, Check, FileText, Film, Plus, UploadCloud, X } from 'lucide-react';
import { type DragEvent, type FormEvent, useEffect, useRef, useState } from 'react';

import {
  attachmentAccept,
  formatFileSize,
  mergeAttachments,
  validateAttachmentFiles,
  validateVideoFiles,
  videoAccept,
} from '@/features/wireframes/registration-files';
import styles from '@/features/wireframes/video-registration.module.css';

export interface VideoRegistrationDraft {
  video: File;
  attachments: File[];
  sourceType: 'raw' | 'broadcast';
  broadcastDate: string;
}

export interface RegisteredVideo {
  id: string;
  fileName: string;
  fileSize: number;
  attachments: string[];
  sourceType: VideoRegistrationDraft['sourceType'];
  broadcastDate: string;
}

interface VideoRegistrationProps {
  isNavigating: boolean;
  onCancel: () => void;
  onRegister: (draft: VideoRegistrationDraft) => void;
}

interface FileDropzoneProps {
  kind: 'video' | 'attachment';
  error: string;
  isDisabled: boolean;
  hasVideo?: boolean;
  onFiles: (files: File[]) => void;
}

function FileDropzone({ kind, error, isDisabled, hasVideo, onFiles }: FileDropzoneProps) {
  const [isDragging, setIsDragging] = useState(false);
  const dragDepth = useRef(0);
  const isVideo = kind === 'video';

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault();
    event.stopPropagation();
    dragDepth.current = 0;
    setIsDragging(false);
    if (!isDisabled) onFiles(Array.from(event.dataTransfer.files));
  }

  return (
    <>
      <label
        className={styles.dropzone}
        data-kind={kind}
        data-dragging={isDragging}
        data-invalid={Boolean(error)}
        onDragEnter={(event) => {
          event.preventDefault();
          dragDepth.current += 1;
          if (!isDisabled) setIsDragging(true);
        }}
        onDragLeave={() => {
          dragDepth.current = Math.max(0, dragDepth.current - 1);
          if (dragDepth.current === 0) setIsDragging(false);
        }}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = isDisabled ? 'none' : 'copy';
        }}
        onDrop={handleDrop}
      >
        <input
          accept={isVideo ? videoAccept : attachmentAccept}
          aria-describedby={error ? `${kind}-error` : `${kind}-hint`}
          aria-invalid={Boolean(error)}
          aria-required={isVideo}
          aria-label={isVideo ? '영상 파일 선택' : '첨부 파일 선택'}
          className={styles.fileInput}
          disabled={isDisabled}
          id={`${kind}-file`}
          multiple={!isVideo}
          onChange={(event) => {
            const files = Array.from(event.currentTarget.files ?? []);
            if (files.length) onFiles(files);
            event.currentTarget.value = '';
          }}
          type="file"
        />
        <span className={styles.uploadIcon}>
          {isVideo ? <UploadCloud aria-hidden="true" /> : <Plus aria-hidden="true" />}
        </span>
        <span className={styles.dropCopy}>
          <strong>
            {isVideo
              ? hasVideo
                ? '다른 영상으로 변경하기'
                : '영상을 여기에 드래그해 주세요'
              : '첨부 파일 추가하기'}
          </strong>
          <span id={`${kind}-hint`}>
            {isVideo
              ? '또는 클릭하여 내 컴퓨터에서 파일 선택 · 영상 1개'
              : '드래그하거나 클릭하여 선택 · TXT, SRT, VTT'}
          </span>
        </span>
        {isVideo ? <span className={styles.chooseFile}>파일 선택</span> : null}
      </label>
      {error ? (
        <p className={styles.error} id={`${kind}-error`} role="alert">
          {error}
        </p>
      ) : null}
    </>
  );
}

export function VideoRegistration({ isNavigating, onCancel, onRegister }: VideoRegistrationProps) {
  const [video, setVideo] = useState<File | null>(null);
  const [attachments, setAttachments] = useState<File[]>([]);
  const [sourceType, setSourceType] = useState<VideoRegistrationDraft['sourceType']>('broadcast');
  const [videoError, setVideoError] = useState('');
  const [attachmentError, setAttachmentError] = useState('');
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const hasSubmitted = useRef(false);

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, []);

  function handleVideoFiles(files: File[]) {
    const error = validateVideoFiles(files);
    setVideoError(error);
    if (!error) setVideo(files[0]);
  }

  function handleAttachmentFiles(files: File[]) {
    const error = validateAttachmentFiles(files);
    setAttachmentError(error);
    if (!error) setAttachments((current) => mergeAttachments(current, files));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isNavigating || hasSubmitted.current) return;
    if (!video) {
      setVideoError('등록할 영상 파일을 선택해 주세요.');
      formRef.current?.querySelector<HTMLInputElement>('#video-file')?.focus();
      return;
    }
    if (videoError || attachmentError) {
      formRef.current
        ?.querySelector<HTMLInputElement>(videoError ? '#video-file' : '#attachment-file')
        ?.focus();
      return;
    }
    hasSubmitted.current = true;
    const broadcastDate = String(new FormData(event.currentTarget).get('broadcastDate') ?? '');
    onRegister({ video, attachments, sourceType, broadcastDate });
  }

  return (
    <div className={styles.page}>
      <button
        className={styles.backButton}
        disabled={isNavigating}
        onClick={onCancel}
        type="button"
      >
        <ArrowLeft aria-hidden="true" /> 문의 목록으로
      </button>
      <header className={styles.heading}>
        <p>VIDEO UPLOAD</p>
        <h1 ref={headingRef} tabIndex={-1}>
          영상 등록
        </h1>
        <span>새로운 영상을 추가하고, 필요한 장면을 더 쉽게 찾아보세요.</span>
      </header>
      <form
        aria-label="영상 등록"
        className={styles.form}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => event.preventDefault()}
        onSubmit={handleSubmit}
        ref={formRef}
      >
        <p aria-live="polite" className={styles.srOnly} role="status">
          {video ? `${video.name} 선택됨.` : '선택한 영상이 없습니다.'} 첨부 파일{' '}
          {attachments.length}개 선택됨.
        </p>
        <section aria-labelledby="video-label" className={styles.section}>
          <h2 id="video-label">
            영상 파일 <span className={styles.required}>필수</span>
          </h2>
          <FileDropzone
            error={videoError}
            hasVideo={Boolean(video)}
            isDisabled={isNavigating}
            kind="video"
            onFiles={handleVideoFiles}
          />
          {video ? (
            <div className={styles.fileRow}>
              <Film aria-hidden="true" />
              <span>
                <strong>{video.name}</strong>
                <small>{formatFileSize(video.size)}</small>
              </span>
              <button
                aria-label="영상 파일 삭제"
                disabled={isNavigating}
                onClick={() => {
                  setVideo(null);
                  setVideoError('');
                }}
                type="button"
              >
                <X aria-hidden="true" />
              </button>
            </div>
          ) : null}
        </section>
        <section aria-labelledby="attachment-label" className={styles.section}>
          <h2 id="attachment-label">
            첨부 파일 <span>선택</span>
          </h2>
          <p className={styles.description}>대본, 자막 텍스트 파일을 함께 업로드 할 수 있어요.</p>
          <FileDropzone
            error={attachmentError}
            isDisabled={isNavigating}
            kind="attachment"
            onFiles={handleAttachmentFiles}
          />
          {attachments.length > 0 ? (
            <ul aria-label="선택한 첨부 파일" className={styles.files}>
              {attachments.map((file, index) => (
                <li
                  className={styles.fileRow}
                  key={`${file.name}-${file.size}-${file.lastModified}`}
                >
                  <FileText aria-hidden="true" />
                  <span>
                    <strong>{file.name}</strong>
                    <small>{formatFileSize(file.size)}</small>
                  </span>
                  <button
                    aria-label={`${file.name} 삭제`}
                    disabled={isNavigating}
                    onClick={() => {
                      setAttachments((current) =>
                        current.filter((_, itemIndex) => itemIndex !== index),
                      );
                      setAttachmentError('');
                    }}
                    type="button"
                  >
                    <X aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {attachmentError ? (
            <button
              className={styles.clearError}
              onClick={() => setAttachmentError('')}
              type="button"
            >
              선택한 첨부 파일만 유지하기
            </button>
          ) : null}
        </section>
        <div className={styles.metadata}>
          <fieldset className={styles.sourceType} disabled={isNavigating}>
            <legend>영상 형식</legend>
            <div>
              {(
                [
                  ['raw', '원본'],
                  ['broadcast', '방영본'],
                ] as const
              ).map(([value, label]) => (
                <label key={value}>
                  <input
                    checked={sourceType === value}
                    name="sourceType"
                    onChange={() => setSourceType(value)}
                    type="radio"
                    value={value}
                  />
                  <span>
                    <Check aria-hidden="true" />
                    {label}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <div className={styles.dateField}>
            <label htmlFor="broadcast-date">
              방영일 <span>선택</span>
            </label>
            <input disabled={isNavigating} id="broadcast-date" name="broadcastDate" type="date" />
          </div>
        </div>
        <footer className={styles.footer}>
          <p>화면 확인용 데모입니다. 파일은 서버로 전송되지 않습니다.</p>
          <div>
            <button
              className={styles.cancelButton}
              disabled={isNavigating}
              onClick={onCancel}
              type="button"
            >
              취소
            </button>
            <button className={styles.submitButton} disabled={isNavigating} type="submit">
              {isNavigating ? '등록 중…' : '등록'}
            </button>
          </div>
        </footer>
      </form>
    </div>
  );
}
