'use client';

import { Check, FileText, Film, Plus, UploadCloud, X } from 'lucide-react';
import { type DragEvent, useEffect, useRef, useState } from 'react';

import {
  formatFileSize,
  formatVideoDetails,
  videoAccept,
} from '@/features/wireframes/registration-files';
import styles from '@/features/wireframes/video-registration.module.css';

export type DropzoneKind = 'video' | 'subtitle' | 'script';

interface FileDropzoneProps {
  accept: string;
  /** 선택한 파일의 용량 뒤에 붙일 정보. 없으면 용량만 표시한다. */
  details?: string;
  error: string;
  hasFile: boolean;
  hint?: string;
  isDisabled: boolean;
  isChecking?: boolean;
  kind: DropzoneKind;
  label: string;
  onFiles: (files: File[]) => void;
  selectedFile?: File | null;
  onRemove?: () => void;
}

export function FileDropzone({
  accept,
  details,
  error,
  hasFile,
  hint,
  isDisabled,
  isChecking = false,
  kind,
  label,
  onFiles,
  selectedFile,
  onRemove,
}: FileDropzoneProps) {
  const [isDragging, setIsDragging] = useState(false);
  const dragDepth = useRef(0);
  const isVideo = kind === 'video';

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault();
    event.stopPropagation();
    dragDepth.current = 0;
    setIsDragging(false);
    const files = Array.from(event.dataTransfer.files);
    if (!isDisabled && files.length > 0) onFiles(files);
  }

  return (
    <div className={styles.dropzoneGroup}>
      <label
        className={styles.dropzone}
        data-kind={kind}
        data-dragging={isDragging}
        data-invalid={Boolean(error)}
        data-selected={hasFile}
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
          aria-describedby={[
            error ? `${kind}-error` : hint ? `${kind}-hint` : `${kind}-desc`,
            selectedFile ? `${kind}-selection` : '',
          ]
            .filter(Boolean)
            .join(' ')}
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
          {isVideo ? (
            selectedFile ? (
              <Film aria-hidden="true" />
            ) : (
              <UploadCloud aria-hidden="true" />
            )
          ) : (
            <Plus aria-hidden="true" />
          )}
        </span>
        <span className={styles.dropCopy}>
          {selectedFile ? (
            <span className={styles.selectedVideo} id={`${kind}-selection`}>
              <span className={styles.selectionStatus}>
                <Check aria-hidden="true" /> 선택됨
              </span>
              <strong>{selectedFile.name}</strong>
              <span>{details ?? formatFileSize(selectedFile.size)}</span>
            </span>
          ) : (
            <strong>
              {isChecking
                ? '파일 내용을 확인하고 있어요…'
                : hasFile
                  ? `${label} 재선택`
                  : `${label} 추가하기`}
            </strong>
          )}
          {hint ? <span id={`${kind}-hint`}>{hint}</span> : null}
        </span>
        {isVideo ? <span className={styles.chooseFile}>파일 선택</span> : null}
      </label>
      {selectedFile && onRemove ? (
        <button
          aria-label={`${label} 삭제`}
          className={styles.removeVideo}
          disabled={isDisabled}
          onClick={onRemove}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      ) : null}
      {error ? <FieldError id={`${kind}-error`}>{error}</FieldError> : null}
    </div>
  );
}

export function SelectedFileRow({
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
    <li className={styles.fileRow}>
      {label === '영상 파일' ? <Film aria-hidden="true" /> : <FileText aria-hidden="true" />}
      <span>
        <span className={styles.selectionStatus}>
          <Check aria-hidden="true" /> 선택됨
        </span>
        <strong>{file.name}</strong>
        <small>{formatFileSize(file.size)}</small>
      </span>
      <button aria-label={`${label} 삭제`} disabled={isDisabled} onClick={onRemove} type="button">
        <X aria-hidden="true" />
      </button>
    </li>
  );
}

export function FieldError({ children, id }: { children: string; id: string }) {
  return (
    <p className={styles.error} id={id} role="alert">
      {children}
    </p>
  );
}

interface VideoFileFieldProps {
  error: string;
  file: File | null;
  isChecking: boolean;
  isDisabled: boolean;
  onFiles: (files: File[]) => void;
  onRemove: () => void;
}

interface VideoPreview {
  file: File;
  duration?: number;
  width?: number;
  height?: number;
  isUnplayable?: boolean;
}

export function VideoFileField({
  error,
  file,
  isChecking,
  isDisabled,
  onFiles,
  onRemove,
}: VideoFileFieldProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  // 미디어 이벤트로만 채운다. 파일을 바꾸면 이전 파일의 길이·재생 불가 표시가 남지 않게 파일로 가른다.
  const [loaded, setLoaded] = useState<VideoPreview | null>(null);
  const current = loaded?.file === file ? loaded : null;

  useEffect(() => {
    const video = videoRef.current;
    if (!file || !video) return;
    // 미리보기는 브라우저 안에서만 재생한다. 파일을 바꾸거나 지우거나 화면을 떠나면 URL 을 해제한다.
    const url = URL.createObjectURL(file);
    video.src = url;
    return () => URL.revokeObjectURL(url);
  }, [file]);

  return (
    // 파일 유무와 관계없이 같은 wrapper 를 둬야 파일 input 이 다시 만들어지지 않고 포커스가 남는다.
    <div className={styles.videoCard} data-has-file={Boolean(file)}>
      {file ? (
        // 파일 선택 label 밖에 둬야 재생 컨트롤을 눌러도 파일 대화상자가 열리지 않는다.
        <video
          aria-label="선택한 영상 미리보기"
          className={styles.videoPreview}
          controls
          onError={() => setLoaded({ file, isUnplayable: true })}
          onLoadedMetadata={(event) => {
            const { duration, videoWidth, videoHeight } = event.currentTarget;
            setLoaded({ file, duration, width: videoWidth, height: videoHeight });
          }}
          playsInline
          preload="metadata"
          ref={videoRef}
        />
      ) : null}
      {current?.isUnplayable ? (
        <p className={styles.previewNotice} role="status">
          이 브라우저에서는 미리보기를 재생할 수 없어요. 등록은 그대로 할 수 있어요.
        </p>
      ) : null}
      <FileDropzone
        accept={videoAccept}
        details={
          file
            ? formatVideoDetails({
                size: file.size,
                duration: current?.duration,
                width: current?.width,
                height: current?.height,
              })
            : undefined
        }
        error={error}
        hasFile={Boolean(file)}
        hint={file ? undefined : '드래그하거나 클릭하여 선택 · 영상 1개'}
        isDisabled={isDisabled}
        isChecking={isChecking}
        kind="video"
        label="영상 파일"
        onFiles={onFiles}
        selectedFile={file}
        onRemove={onRemove}
      />
    </div>
  );
}
