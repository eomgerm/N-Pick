'use client';

import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState, useSyncExternalStore } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  type DropzoneKind,
  FieldError,
  FileDropzone,
  VideoFileField,
} from '@/features/wireframes/registration-file-fields';
import { RegistrationDatePicker } from '@/features/wireframes/registration-date-picker';
import { RegistrationUploadProgress } from '@/features/wireframes/registration-upload-progress';
import type { UploadProgress } from '@/features/wireframes/registration-upload-phase';
import {
  registrationDateBounds,
  registrationToday,
  validateRegistrationDates,
} from '@/features/wireframes/registration-dates';
import {
  scriptAccept,
  subtitleAccept,
  validateScriptContent,
  validateScriptFiles,
  validateSubtitleContent,
  validateSubtitleFiles,
  validateVideoContent,
  validateVideoFiles,
} from '@/features/wireframes/registration-files';
import {
  type ClipRegistrationErrorPresentation,
  type ClipRegistrationOutcome,
  type ClipRegistrationSubmission,
  type ClipSourceType,
  createClipRegistrationSubmission,
  getClipRegistrationErrorPresentation,
  type RegistrationField,
  type RegistrationFieldErrors,
  registerClip,
} from '@/features/wireframes/video-registration-api';
import styles from '@/features/wireframes/video-registration.module.css';
import {
  CLIP_TITLE_MAX_LENGTH,
  rejectOversizedPaste,
} from '@/features/wireframes/input-validation';

export interface RegisteredVideo {
  id: string;
  pipelineRunId: string;
  status: 'queued';
  /** 서버가 새 clip 을 만들었는지, 이미 있던 clip 을 돌려주었는지. 등록 결과 안내 문구를 가른다. */
  outcome: ClipRegistrationOutcome;
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
  onRegister: (video: RegisteredVideo) => void;
}

const subscribeToNothing = () => () => {};
const getNoToday = () => '';

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

export function VideoRegistrationHeading() {
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
    </div>
  );
}

export function VideoRegistration({
  isNavigating,
  onBusyChange,
  onRegister,
}: VideoRegistrationProps) {
  const [video, setVideo] = useState<File | null>(null);
  const [subtitle, setSubtitle] = useState<File | null>(null);
  const [script, setScript] = useState<File | null>(null);
  const [checkingFiles, setCheckingFiles] = useState<Partial<Record<DropzoneKind, boolean>>>({});
  const fileChecks = useRef({ video: 0, subtitle: 0, script: 0 });
  const pendingFileChecks = useRef(new Set<DropzoneKind>());
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
  // 요청 중에만 채운다. 폼 위 대기 화면이 파일 이름·전송량을 보여 준다 (S15P21A501-325).
  const [upload, setUpload] = useState<{
    fileName: string;
    fileSize: number;
    progress: UploadProgress | null;
  } | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const globalErrorRef = useRef<HTMLDivElement>(null);
  const lockedRef = useRef(false);
  const activeSubmissionRef = useRef<ClipRegistrationSubmission | null>(null);
  const retrySubmissionRef = useRef<ClipRegistrationSubmission | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      const submission = activeSubmissionRef.current;
      if (!submission) throw new Error('Missing registration submission.');
      return registerClip(submission, undefined, (progress) =>
        setUpload((current) => (current ? { ...current, progress } : current)),
      );
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
        outcome: result.outcome,
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
      setUpload(null);
      activeSubmissionRef.current = null;
      lockedRef.current = false;
      setIsSubmissionLocked(false);
      onBusyChange(false);
    },
  });
  const isBusy = isNavigating || mutation.isPending || isSubmissionLocked;
  const isCheckingFiles = Object.values(checkingFiles).some(Boolean);
  // 영상을 고르기 전에는 나머지 입력을 잠근다. 재선택 검사 중에는 잠그지 않아 포커스·입력이 유지된다.
  const isLocked = !video && !checkingFiles.video;
  // 서버에서는 빈 값을 주고 마운트 후에 로컬 오늘로 바꾼다. 렌더 중에 오늘을 읽으면 서버·브라우저 시간대 차이로
  // hydration 이 어긋난다.
  const today = useSyncExternalStore(subscribeToNothing, registrationToday, getNoToday);
  const dateBounds = registrationDateBounds({ sourceType, broadcastDate, filmedDate }, today);

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
    setFieldErrors((current) => ({
      ...current,
      [field]: undefined,
      // 날짜 안내는 두 값의 관계로 정해진다. 한쪽을 고치면 반대쪽에 붙은 안내도 더는 사실이 아니다.
      ...(field === 'broadcastDate' || field === 'filmedDate'
        ? { broadcastDate: undefined, filmedDate: undefined }
        : {}),
    }));
  }

  async function handleFiles(kind: DropzoneKind, files: File[]) {
    if (lockedRef.current || isNavigating || files.length === 0) return;
    const { field, label, setFile, validateFiles, validateContent } = {
      video: {
        field: 'video',
        label: '영상 파일',
        setFile: setVideo,
        validateFiles: validateVideoFiles,
        validateContent: validateVideoContent,
      },
      subtitle: {
        field: 'subtitle',
        label: '자막 파일',
        setFile: setSubtitle,
        validateFiles: validateSubtitleFiles,
        validateContent: validateSubtitleContent,
      },
      script: {
        field: 'scriptText',
        label: '일반 대본 파일',
        setFile: setScript,
        validateFiles: validateScriptFiles,
        validateContent: validateScriptContent,
      },
    }[kind];
    const check = ++fileChecks.current[kind];
    markEdited(field as RegistrationField);
    setFile(null);
    pendingFileChecks.current.add(kind);
    setCheckingFiles((current) => ({ ...current, [kind]: true }));
    setLiveMessage(`${label} 내용을 확인하고 있어요.`);
    const error = validateFiles(files) || (await validateContent(files[0]));
    // 먼저 고른 파일의 느린 읽기 결과가 나중에 고른 파일을 덮어쓰지 않게 한다.
    if (check !== fileChecks.current[kind]) return;
    pendingFileChecks.current.delete(kind);
    setCheckingFiles((current) => ({ ...current, [kind]: false }));
    setFieldErrors((current) => ({ ...current, [field]: error || undefined }));
    if (error) {
      setLiveMessage(`${label}을 선택하지 못했습니다. ${error}`);
      return;
    }
    setFile(files[0]);
    setLiveMessage(`${label} ${files[0].name}이 선택되었습니다.`);
  }

  function validateForm(): RegistrationFieldErrors {
    const errors: RegistrationFieldErrors = {};
    const videoError = validateVideoFiles(video ? [video] : []);
    const subtitleError = validateSubtitleFiles(subtitle ? [subtitle] : []);
    const scriptError = validateScriptFiles(script ? [script] : []);
    if (videoError) errors.video = videoError;
    if (title.length > CLIP_TITLE_MAX_LENGTH)
      errors.title = `제목은 ${CLIP_TITLE_MAX_LENGTH}자 이내로 입력해 주세요.`;
    Object.assign(errors, validateRegistrationDates({ sourceType, broadcastDate, filmedDate }));
    if (subtitleError) errors.subtitle = subtitleError;
    if (scriptError) errors.scriptText = scriptError;
    if (!rightsConfirmed) errors.rightsConfirmed = '등록 전 확인 내용에 체크해주세요.';
    if (!externalProcessingConfirmed) {
      errors.externalProcessingConfirmed = '등록 전 확인 내용에 체크해주세요.';
    }
    return errors;
  }

  function startSubmission(submission: ClipRegistrationSubmission) {
    if (lockedRef.current || isNavigating || pendingFileChecks.current.size > 0) return;
    lockedRef.current = true;
    setIsSubmissionLocked(true);
    activeSubmissionRef.current = submission;
    setUpload({
      fileName: submission.snapshot.video.name,
      fileSize: submission.snapshot.video.size,
      progress: null,
    });
    setErrorPresentation(null);
    setFieldErrors({});
    setLiveMessage('일반 대본을 확인하고 영상 등록 요청을 보내고 있어요.');
    onBusyChange(true);
    mutation.reset();
    mutation.mutate();
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (lockedRef.current || isNavigating || pendingFileChecks.current.size > 0) return;
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
        aria-busy={isBusy || isCheckingFiles}
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

        <div className={styles.videoLayout}>
          <section aria-labelledby="video-label" className={styles.section}>
            <h2 id="video-label">
              영상 파일 <span className={styles.required}>필수</span>
            </h2>
            <p className={styles.description} id="video-desc">
              등록할 영상을 업로드 해주세요. (MP4/MOV 지원)
            </p>
            <VideoFileField
              error={fieldErrors.video ?? ''}
              file={video}
              isDisabled={isBusy}
              isChecking={Boolean(checkingFiles.video)}
              onFiles={(files) => void handleFiles('video', files)}
              onRemove={() => {
                markEdited('video');
                setVideo(null);
                setLiveMessage(`영상 파일 ${video?.name}이 삭제되었습니다.`);
              }}
            />
          </section>
          <div className={styles.detailsColumn} data-locked={isLocked} inert={isLocked}>
            <section aria-labelledby="metadata-label" className={styles.section}>
              <h2 id="metadata-label">영상 정보</h2>
              <div className={styles.metadata}>
                <fieldset
                  aria-describedby={fieldErrors.sourceType ? 'source-type-error' : undefined}
                  className={styles.sourceType}
                  disabled={isBusy}
                >
                  <legend>
                    영상 종류 <span className={styles.required}>필수</span>
                  </legend>
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
                        <span>{label}</span>
                      </label>
                    ))}
                  </div>
                  {fieldErrors.sourceType ? (
                    <FieldError id="source-type-error">{fieldErrors.sourceType}</FieldError>
                  ) : null}
                </fieldset>
                <label className={styles.textField}>
                  <span>
                    제목 <small>선택 · 최대 {CLIP_TITLE_MAX_LENGTH}자</small>
                  </span>
                  <input
                    aria-describedby={fieldErrors.title ? 'title-error' : 'title-hint'}
                    aria-invalid={Boolean(fieldErrors.title)}
                    disabled={isBusy}
                    id="registration-title"
                    maxLength={CLIP_TITLE_MAX_LENGTH}
                    onPaste={(event) =>
                      rejectOversizedPaste(event, CLIP_TITLE_MAX_LENGTH, () =>
                        setFieldErrors((current) => ({
                          ...current,
                          title: `제목은 ${CLIP_TITLE_MAX_LENGTH}자 이내로 입력해 주세요.`,
                        })),
                      )
                    }
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
                  <RegistrationDatePicker
                    error={fieldErrors.broadcastDate}
                    isDisabled={isBusy}
                    id="broadcast-date"
                    label="방송일"
                    maxDate={dateBounds.broadcastMax}
                    minDate={dateBounds.broadcastMin}
                    onChange={(date) => {
                      markEdited('broadcastDate');
                      setBroadcastDate(date);
                    }}
                    value={broadcastDate}
                  />
                ) : (
                  <p className={styles.archiveDateNotice}>
                    자료 영상에는 방송일 입력이 불가합니다.
                  </p>
                )}
                <RegistrationDatePicker
                  error={fieldErrors.filmedDate}
                  isDisabled={isBusy}
                  id="filmed-date"
                  label="촬영일"
                  maxDate={dateBounds.filmedMax}
                  minDate={dateBounds.filmedMin}
                  onChange={(date) => {
                    markEdited('filmedDate');
                    setFilmedDate(date);
                  }}
                  value={filmedDate}
                />
              </div>
            </section>

            <section aria-labelledby="supplement-label" className={styles.section}>
              <h2 id="supplement-label">
                추가 자료 <span>선택</span>
              </h2>
              <div className={styles.attachmentGrid}>
                <div>
                  <FileDropzone
                    accept={subtitleAccept}
                    error={fieldErrors.subtitle ?? ''}
                    hasFile={Boolean(subtitle)}
                    hint={subtitle ? undefined : 'SRT·VTT·JSON 1개 · 10 MiB 이하'}
                    isDisabled={isBusy}
                    isChecking={checkingFiles.subtitle}
                    kind="subtitle"
                    label="자막 파일"
                    onFiles={(files) => void handleFiles('subtitle', files)}
                    selectedFile={subtitle}
                    onRemove={() => {
                      markEdited('subtitle');
                      setSubtitle(null);
                      setLiveMessage(`자막 파일 ${subtitle?.name}이 삭제되었습니다.`);
                    }}
                  />
                </div>
                <div>
                  <FileDropzone
                    accept={scriptAccept}
                    error={fieldErrors.scriptText ?? ''}
                    hasFile={Boolean(script)}
                    hint={script ? undefined : 'UTF-8 TXT 1개'}
                    isDisabled={isBusy}
                    isChecking={checkingFiles.script}
                    kind="script"
                    label="일반 대본 파일"
                    onFiles={(files) => void handleFiles('script', files)}
                    selectedFile={script}
                    onRemove={() => {
                      markEdited('scriptText');
                      setScript(null);
                      setLiveMessage(`일반 대본 파일 ${script?.name}이 삭제되었습니다.`);
                    }}
                  />
                </div>
              </div>
            </section>
          </div>
        </div>

        <div className={styles.submitArea}>
          {/* 영상을 고르기 전에는 동의를 잠그고, 등록 버튼은 비활성으로 둔다. */}
          <fieldset
            className={styles.confirmations}
            data-locked={isLocked}
            disabled={isBusy}
            inert={isLocked}
          >
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
                    fieldErrors.externalProcessingConfirmed
                      ? 'external-processing-error'
                      : undefined
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
              <ApiErrorNotice
                error={mutation.error}
                id="registration-api-error"
                message={errorPresentation.globalMessage}
              />
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
                className={styles.submitButton}
                disabled={isBusy || isCheckingFiles || isLocked}
                type="submit"
              >
                {isBusy
                  ? '등록 중…'
                  : isCheckingFiles
                    ? '파일 확인 중…'
                    : errorPresentation?.retryMode === 'same-request'
                      ? '다시 시도'
                      : '등록'}
              </button>
            </div>
          </footer>
        </div>
      </form>
      {upload ? <RegistrationUploadProgress {...upload} /> : null}
    </div>
  );
}
