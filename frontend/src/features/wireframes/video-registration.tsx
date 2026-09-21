'use client';

import { useMutation } from '@tanstack/react-query';
import { ArrowLeft, Check, FileText, Film, Plus, UploadCloud, X } from 'lucide-react';
import { type DragEvent, type FormEvent, useEffect, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  formatFileSize,
  scriptAccept,
  subtitleAccept,
  validateScriptFiles,
  validateSubtitleFiles,
  validateVideoFiles,
  videoAccept,
} from '@/features/wireframes/registration-files';
import {
  type ClipRegistrationErrorPresentation,
  type ClipRegistrationSubmission,
  type ClipSourceType,
  createClipRegistrationSubmission,
  getClipRegistrationErrorPresentation,
  type RegistrationField,
  type RegistrationFieldErrors,
  registerClip,
} from '@/features/wireframes/video-registration-api';
import styles from '@/features/wireframes/video-registration.module.css';

export interface RegisteredVideo {
  id: string;
  pipelineRunId: string;
  status: 'queued';
  title: string;
  fileName: string;
  fileSize: number;
  sourceType: ClipSourceType;
  broadcastDate: string;
  filmedDate: string;
  subtitleFileName?: string;
  scriptFileName?: string;
}

interface VideoRegistrationProps {
  isNavigating: boolean;
  onBusyChange: (isBusy: boolean) => void;
  onCancel: () => void;
  onRegister: (video: RegisteredVideo) => void;
}

interface VideoRegistrationHeadingProps {
  isDisabled: boolean;
  onBack: () => void;
}

type DropzoneKind = 'video' | 'subtitle' | 'script';

interface FileDropzoneProps {
  accept: string;
  error: string;
  hasFile: boolean;
  hint?: string;
  isDisabled: boolean;
  kind: DropzoneKind;
  label: string;
  onFiles: (files: File[]) => void;
}

const focusSelectors: Record<RegistrationField, string> = {
  video: '#video-file',
  sourceType: 'input[name="sourceType"]',
  title: '#registration-title',
  broadcastDate: '#broadcast-date',
  filmedDate: '#filmed-date',
  subtitle: '#subtitle-file',
  scriptText: '#script-file',
  rightsConfirmed: '#rights-confirmed',
  externalProcessingConfirmed: '#external-processing-confirmed',
};

function FileDropzone({
  accept,
  error,
  hasFile,
  hint,
  isDisabled,
  kind,
  label,
  onFiles,
}: FileDropzoneProps) {
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
          accept={accept}
          aria-describedby={error ? `${kind}-error` : hint ? `${kind}-hint` : `${kind}-desc`}
          aria-invalid={Boolean(error)}
          aria-label={`${label} 선택`}
          aria-required={isVideo}
          className={styles.fileInput}
          disabled={isDisabled}
          id={`${kind}-file`}
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
          <strong>{hasFile ? `${label} 재선택` : `${label} 추가하기`}</strong>
          {hint ? <span id={`${kind}-hint`}>{hint}</span> : null}
        </span>
        {isVideo ? <span className={styles.chooseFile}>파일 선택</span> : null}
      </label>
      {error ? <FieldError id={`${kind}-error`}>{error}</FieldError> : null}
    </>
  );
}

function SelectedFileRow({
  file,
  isDisabled,
  label,
  onRemove,
}: {
  file: File;
  isDisabled: boolean;
  label: string;
  onRemove: () => void;
}) {
  return (
    <div className={styles.fileRow}>
      {label === '영상 파일' ? <Film aria-hidden="true" /> : <FileText aria-hidden="true" />}
      <span>
        <strong>{file.name}</strong>
        <small>{formatFileSize(file.size)}</small>
      </span>
      <button aria-label={`${label} 삭제`} disabled={isDisabled} onClick={onRemove} type="button">
        <X aria-hidden="true" />
      </button>
    </div>
  );
}

function FieldError({ children, id }: { children: string; id: string }) {
  return (
    <p className={styles.error} id={id} role="alert">
      {children}
    </p>
  );
}

export function VideoRegistrationHeading({ isDisabled, onBack }: VideoRegistrationHeadingProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, []);

  return (
    <div className={styles.heading}>
      <h1 ref={headingRef} tabIndex={-1}>
        영상 등록
      </h1>
      <button className={styles.backButton} disabled={isDisabled} onClick={onBack} type="button">
        <ArrowLeft aria-hidden="true" /> 문의 목록으로
      </button>
    </div>
  );
}

export function VideoRegistration({
  isNavigating,
  onBusyChange,
  onCancel,
  onRegister,
}: VideoRegistrationProps) {
  const [video, setVideo] = useState<File | null>(null);
  const [subtitle, setSubtitle] = useState<File | null>(null);
  const [script, setScript] = useState<File | null>(null);
  const [sourceType, setSourceType] = useState<ClipSourceType>('broadcast');
  const [title, setTitle] = useState('');
  const [broadcastDate, setBroadcastDate] = useState('');
  const [filmedDate, setFilmedDate] = useState('');
  const [rightsConfirmed, setRightsConfirmed] = useState(false);
  const [externalProcessingConfirmed, setExternalProcessingConfirmed] = useState(false);
  const [isSubmissionLocked, setIsSubmissionLocked] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<RegistrationFieldErrors>({});
  const [errorPresentation, setErrorPresentation] =
    useState<ClipRegistrationErrorPresentation | null>(null);
  const [liveMessage, setLiveMessage] = useState('영상 등록 내용을 입력할 수 있어요.');
  const formRef = useRef<HTMLFormElement>(null);
  const globalErrorRef = useRef<HTMLDivElement>(null);
  const lockedRef = useRef(false);
  const activeSubmissionRef = useRef<ClipRegistrationSubmission | null>(null);
  const retrySubmissionRef = useRef<ClipRegistrationSubmission | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      const submission = activeSubmissionRef.current;
      if (!submission) throw new Error('Missing registration submission.');
      return registerClip(submission);
    },
    gcTime: 0,
    onSuccess: (result) => {
      const submission = activeSubmissionRef.current;
      if (!submission) return;
      const snapshot = submission.snapshot;
      retrySubmissionRef.current = null;
      onRegister({
        id: result.clipId,
        pipelineRunId: result.pipelineRunId,
        status: result.status,
        title: snapshot.title,
        fileName: snapshot.video.name,
        fileSize: snapshot.video.size,
        sourceType: snapshot.sourceType,
        broadcastDate: snapshot.broadcastDate,
        filmedDate: snapshot.filmedDate,
        subtitleFileName: snapshot.subtitle?.name,
        scriptFileName: snapshot.script?.name,
      });
    },
    onError: (error) => {
      const presentation = getClipRegistrationErrorPresentation(error);
      setErrorPresentation(presentation);
      setFieldErrors(presentation.fieldErrors);
      retrySubmissionRef.current =
        presentation.retryMode === 'same-request' ? activeSubmissionRef.current : null;
      setLiveMessage(
        presentation.retryMode === 'same-request'
          ? '등록 결과를 확인하지 못했습니다. 입력을 바꾸지 않고 같은 요청으로 다시 시도할 수 있어요.'
          : presentation.retryMode === 'new-request'
            ? '기존 요청 키를 사용할 수 없습니다. 새 요청으로 다시 제출해 주세요.'
            : '영상 등록에 실패했습니다. 안내된 입력을 확인해 주세요.',
      );
      scheduleErrorFocus(presentation.fieldErrors, presentation.showGlobal);
    },
    onSettled: () => {
      activeSubmissionRef.current = null;
      lockedRef.current = false;
      setIsSubmissionLocked(false);
      onBusyChange(false);
    },
  });
  const isBusy = isNavigating || mutation.isPending || isSubmissionLocked;

  function scheduleErrorFocus(errors: RegistrationFieldErrors, showGlobal: boolean) {
    const firstField = Object.keys(focusSelectors).find(
      (field) => errors[field as RegistrationField],
    ) as RegistrationField | undefined;
    requestAnimationFrame(() => {
      if (firstField) {
        formRef.current?.querySelector<HTMLElement>(focusSelectors[firstField])?.focus();
      } else if (showGlobal) {
        globalErrorRef.current?.focus();
      }
    });
  }

  function markEdited(field: RegistrationField) {
    retrySubmissionRef.current = null;
    setErrorPresentation(null);
    mutation.reset();
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
  }

  function handleVideoFiles(files: File[]) {
    markEdited('video');
    setVideo(null);
    const error = validateVideoFiles(files);
    setFieldErrors((current) => ({ ...current, video: error || undefined }));
    if (!error) setVideo(files[0]);
  }

  function handleSubtitleFiles(files: File[]) {
    markEdited('subtitle');
    setSubtitle(null);
    const error = validateSubtitleFiles(files);
    setFieldErrors((current) => ({ ...current, subtitle: error || undefined }));
    if (!error) setSubtitle(files[0]);
  }

  function handleScriptFiles(files: File[]) {
    markEdited('scriptText');
    setScript(null);
    const error = validateScriptFiles(files);
    setFieldErrors((current) => ({ ...current, scriptText: error || undefined }));
    if (!error) setScript(files[0]);
  }

  function validateForm(): RegistrationFieldErrors {
    const errors: RegistrationFieldErrors = {};
    const videoError = validateVideoFiles(video ? [video] : []);
    const subtitleError = validateSubtitleFiles(subtitle ? [subtitle] : []);
    const scriptError = validateScriptFiles(script ? [script] : []);
    if (videoError) errors.video = videoError;
    if (title.length > 500) errors.title = '제목은 500자 이내로 입력해 주세요.';
    if (subtitleError) errors.subtitle = subtitleError;
    if (scriptError) errors.scriptText = scriptError;
    if (!rightsConfirmed) errors.rightsConfirmed = '등록 전 확인 내용에 체크해주세요.';
    if (!externalProcessingConfirmed) {
      errors.externalProcessingConfirmed = '등록 전 확인 내용에 체크해주세요.';
    }
    return errors;
  }

  function startSubmission(submission: ClipRegistrationSubmission) {
    if (lockedRef.current || isNavigating) return;
    lockedRef.current = true;
    setIsSubmissionLocked(true);
    activeSubmissionRef.current = submission;
    setErrorPresentation(null);
    setFieldErrors({});
    setLiveMessage('일반 대본을 확인하고 영상 등록 요청을 보내고 있어요.');
    onBusyChange(true);
    mutation.reset();
    mutation.mutate();
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (lockedRef.current || isNavigating) return;
    if (errorPresentation?.retryMode === 'same-request' && retrySubmissionRef.current) {
      startSubmission(retrySubmissionRef.current);
      return;
    }
    if (errorPresentation?.retryMode === 'none') {
      setLiveMessage('오류가 있는 입력을 변경한 뒤 다시 등록해 주세요.');
      scheduleErrorFocus(fieldErrors, errorPresentation.showGlobal);
      return;
    }

    const errors = { ...fieldErrors, ...validateForm() };
    const hasErrors = Object.values(errors).some(Boolean);
    setFieldErrors(errors);
    if (hasErrors || !video || !rightsConfirmed || !externalProcessingConfirmed) {
      setLiveMessage('입력 내용을 확인해 주세요.');
      scheduleErrorFocus(errors, false);
      return;
    }

    const submission = createClipRegistrationSubmission({
      video,
      sourceType,
      title: title.trim(),
      broadcastDate: sourceType === 'broadcast' ? broadcastDate : '',
      filmedDate,
      subtitle,
      script,
      rightsConfirmed: true,
      externalProcessingConfirmed: true,
    });
    retrySubmissionRef.current = null;
    startSubmission(submission);
  }

  return (
    <div className={styles.page}>
      <form
        aria-busy={isBusy}
        aria-label="영상 등록"
        className={styles.form}
        noValidate
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => event.preventDefault()}
        onSubmit={handleSubmit}
        ref={formRef}
      >
        <p aria-live="polite" aria-atomic="true" className={styles.srOnly} role="status">
          {liveMessage}
        </p>

        <section aria-labelledby="video-label" className={styles.section}>
          <h2 id="video-label">
            영상 파일 <span className={styles.required}>필수</span>
          </h2>
          <p className={styles.description} id="video-desc">
            등록할 영상을 업로드 해주세요. (MP4/MOV 지원)
          </p>
          <FileDropzone
            accept={videoAccept}
            error={fieldErrors.video ?? ''}
            hasFile={Boolean(video)}
            hint="드래그하거나 클릭하여 선택 · 영상 1개"
            isDisabled={isBusy}
            kind="video"
            label="영상 파일"
            onFiles={handleVideoFiles}
          />
          {video ? (
            <SelectedFileRow
              file={video}
              isDisabled={isBusy}
              label="영상 파일"
              onRemove={() => {
                markEdited('video');
                setVideo(null);
              }}
            />
          ) : null}
        </section>

        <section aria-labelledby="supplement-label" className={styles.section}>
          <h2 id="supplement-label">
            추가 자료 <span>선택</span>
          </h2>
          <div className={styles.attachmentGrid}>
            <div>
              <h3>자막 파일</h3>
              <p className={styles.description} id="subtitle-desc">
                자막 파일을 선택해주세요 (SRT/VTT 지원)
              </p>
              <FileDropzone
                accept={subtitleAccept}
                error={fieldErrors.subtitle ?? ''}
                hasFile={Boolean(subtitle)}
                isDisabled={isBusy}
                kind="subtitle"
                label="자막 파일"
                onFiles={handleSubtitleFiles}
              />
              {subtitle ? (
                <SelectedFileRow
                  file={subtitle}
                  isDisabled={isBusy}
                  label="자막 파일"
                  onRemove={() => {
                    markEdited('subtitle');
                    setSubtitle(null);
                  }}
                />
              ) : null}
            </div>
            <div>
              <h3>일반 대본</h3>
              <p className={styles.description} id="script-desc">
                대본 파일을 선택해주세요. (TXT 지원)
              </p>
              <FileDropzone
                accept={scriptAccept}
                error={fieldErrors.scriptText ?? ''}
                hasFile={Boolean(script)}
                isDisabled={isBusy}
                kind="script"
                label="일반 대본 파일"
                onFiles={handleScriptFiles}
              />
              {script ? (
                <SelectedFileRow
                  file={script}
                  isDisabled={isBusy}
                  label="일반 대본 파일"
                  onRemove={() => {
                    markEdited('scriptText');
                    setScript(null);
                  }}
                />
              ) : null}
            </div>
          </div>
        </section>

        <section aria-labelledby="metadata-label" className={styles.section}>
          <h2 id="metadata-label">영상 정보</h2>
          <div className={styles.metadata}>
            <fieldset
              aria-describedby={fieldErrors.sourceType ? 'source-type-error' : undefined}
              className={styles.sourceType}
              disabled={isBusy}
            >
              <legend>영상 종류</legend>
              <div>
                {(
                  [
                    ['broadcast', '방송 영상'],
                    ['archive', '자료 영상'],
                  ] as const
                ).map(([value, label]) => (
                  <label key={value}>
                    <input
                      checked={sourceType === value}
                      name="sourceType"
                      onChange={() => {
                        markEdited('sourceType');
                        if (value === 'archive' && broadcastDate) {
                          setBroadcastDate('');
                          setFieldErrors((current) => ({
                            ...current,
                            broadcastDate: undefined,
                          }));
                          setLiveMessage('자료 영상으로 변경해 입력한 방송일을 제외했습니다.');
                        }
                        setSourceType(value);
                      }}
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
              {fieldErrors.sourceType ? (
                <FieldError id="source-type-error">{fieldErrors.sourceType}</FieldError>
              ) : null}
            </fieldset>
            <label className={styles.textField}>
              <span>
                제목 <small>선택 · 최대 500자</small>
              </span>
              <input
                aria-describedby={fieldErrors.title ? 'title-error' : 'title-hint'}
                aria-invalid={Boolean(fieldErrors.title)}
                disabled={isBusy}
                id="registration-title"
                maxLength={500}
                onChange={(event) => {
                  markEdited('title');
                  setTitle(event.target.value);
                }}
                placeholder="영상 제목을 입력하세요"
                type="text"
                value={title}
              />
              <small id="title-hint">비워둘 경우 파일명을 제목으로 사용합니다.</small>
              {fieldErrors.title ? (
                <FieldError id="title-error">{fieldErrors.title}</FieldError>
              ) : null}
            </label>
            {sourceType === 'broadcast' ? (
              <label className={styles.dateField}>
                <span>
                  방송일 <small>선택</small>
                </span>
                <input
                  aria-describedby={fieldErrors.broadcastDate ? 'broadcast-date-error' : undefined}
                  aria-invalid={Boolean(fieldErrors.broadcastDate)}
                  disabled={isBusy}
                  id="broadcast-date"
                  onChange={(event) => {
                    markEdited('broadcastDate');
                    setBroadcastDate(event.target.value);
                  }}
                  type="date"
                  value={broadcastDate}
                />
                {fieldErrors.broadcastDate ? (
                  <FieldError id="broadcast-date-error">{fieldErrors.broadcastDate}</FieldError>
                ) : null}
              </label>
            ) : (
              <p className={styles.archiveDateNotice}>자료 영상에는 방송일 입력이 불가합니다.</p>
            )}
            <label className={styles.dateField}>
              <span>
                촬영일 <small>선택</small>
              </span>
              <input
                aria-describedby={fieldErrors.filmedDate ? 'filmed-date-error' : undefined}
                aria-invalid={Boolean(fieldErrors.filmedDate)}
                disabled={isBusy}
                id="filmed-date"
                onChange={(event) => {
                  markEdited('filmedDate');
                  setFilmedDate(event.target.value);
                }}
                type="date"
                value={filmedDate}
              />
              {fieldErrors.filmedDate ? (
                <FieldError id="filmed-date-error">{fieldErrors.filmedDate}</FieldError>
              ) : null}
            </label>
          </div>
        </section>

        <fieldset className={styles.confirmations} disabled={isBusy}>
          <legend>등록 전 확인</legend>
          <div className={styles.confirmBox}>
            <label data-invalid={Boolean(fieldErrors.rightsConfirmed)}>
              <input
                aria-describedby={fieldErrors.rightsConfirmed ? 'rights-error' : undefined}
                aria-invalid={Boolean(fieldErrors.rightsConfirmed)}
                checked={rightsConfirmed}
                id="rights-confirmed"
                onChange={(event) => {
                  markEdited('rightsConfirmed');
                  setRightsConfirmed(event.target.checked);
                }}
                type="checkbox"
              />
              <span>이 영상과 관련 자료를 등록 및 처리할 권한이 있음을 확인하였습니다.</span>
            </label>
            {fieldErrors.rightsConfirmed ? (
              <FieldError id="rights-error">{fieldErrors.rightsConfirmed}</FieldError>
            ) : null}
            <label data-invalid={Boolean(fieldErrors.externalProcessingConfirmed)}>
              <input
                aria-describedby={
                  fieldErrors.externalProcessingConfirmed ? 'external-processing-error' : undefined
                }
                aria-invalid={Boolean(fieldErrors.externalProcessingConfirmed)}
                checked={externalProcessingConfirmed}
                id="external-processing-confirmed"
                onChange={(event) => {
                  markEdited('externalProcessingConfirmed');
                  setExternalProcessingConfirmed(event.target.checked);
                }}
                type="checkbox"
              />
              <span>처리 과정에서 외부 AI 서비스로 영상이 전송될 수 있음을 확인하였습니다.</span>
            </label>
            {fieldErrors.externalProcessingConfirmed ? (
              <FieldError id="external-processing-error">
                {fieldErrors.externalProcessingConfirmed}
              </FieldError>
            ) : null}
          </div>
        </fieldset>

        {errorPresentation?.showGlobal && mutation.error ? (
          <div className={styles.apiError} ref={globalErrorRef} tabIndex={-1}>
            <ApiErrorNotice error={mutation.error} id="registration-api-error" />
            {errorPresentation.retryMode === 'same-request' ? (
              <div className={styles.retryAction}>
                <p>동일한 요청으로 재시도합니다.</p>
                <button
                  disabled={isBusy}
                  onClick={() => {
                    if (retrySubmissionRef.current) startSubmission(retrySubmissionRef.current);
                  }}
                  type="button"
                >
                  동일 요청 재시도
                </button>
              </div>
            ) : errorPresentation.retryMode === 'new-request' ? (
              <p className={styles.newRequestNotice}>등록 버튼을 눌러 재시도 해주세요.</p>
            ) : null}
          </div>
        ) : null}

        <footer className={styles.footer}>
          <div>
            <button
              className={styles.cancelButton}
              disabled={isBusy}
              onClick={onCancel}
              type="button"
            >
              취소
            </button>
            <button className={styles.submitButton} disabled={isBusy} type="submit">
              {isBusy
                ? '등록 중…'
                : errorPresentation?.retryMode === 'same-request'
                  ? '다시 시도'
                  : '등록'}
            </button>
          </div>
        </footer>
      </form>
    </div>
  );
}
