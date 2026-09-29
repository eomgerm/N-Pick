'use client';

import { Check, CloudUpload, ShieldCheck } from 'lucide-react';
import { useEffect, useRef } from 'react';

import styles from '@/features/wireframes/registration-upload-progress.module.css';
import {
  type UploadProgress,
  uploadPhaseView,
} from '@/features/wireframes/registration-upload-phase';

interface RegistrationUploadProgressProps {
  fileName: string;
  fileSize: number;
  progress: UploadProgress | null;
}

const steps = [
  { phase: 'sending', label: '영상 전송' },
  { phase: 'verifying', label: '서버 확인' },
  { phase: 'processing', label: '처리 화면으로 이동' },
] as const;

// 영상 등록 요청이 끝날 때까지 폼 위에 띄우는 대기 화면 (S15P21A501-325). 폼은 그대로 두어
// 실패하면 입력을 잃지 않고 돌아간다. 성공하면 상위가 처리 상세로 이동시킨다.
export function RegistrationUploadProgress({
  fileName,
  fileSize,
  progress,
}: RegistrationUploadProgressProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const view = uploadPhaseView(progress, fileSize);
  const activeIndex = view.phase === 'sending' ? 0 : 1;

  // showModal() 이 포커스를 대기창 안으로 옮기고 뒤의 폼·페이지를 inert 로 만든다
  // (scene-dialog.tsx 와 같은 방식). 닫힐 때 포커스는 등록 버튼으로 돌려준다 — 오류로
  // 돌아오는 경우 상위의 오류 포커스가 그 뒤에 다시 가져간다.
  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) {
        previousFocus.focus({ preventScroll: true });
      }
    };
  }, []);

  return (
    <dialog
      aria-describedby="registration-upload-detail"
      aria-labelledby="registration-upload-title"
      className={styles.panel}
      // 등록이 끝나야 닫힌다. Esc 로 대기창만 사라지면 요청은 계속 도는데 폼이 되살아난다.
      onCancel={(event) => event.preventDefault()}
      ref={dialogRef}
    >
      <span className={styles.icon} aria-hidden="true" data-phase={view.phase}>
        {view.phase === 'sending' ? <CloudUpload /> : <ShieldCheck />}
      </span>
      <h2 id="registration-upload-title">
        {view.phase === 'sending' ? '영상을 보내고 있어요' : '서버가 영상을 확인하고 있어요'}
      </h2>
      <p className={styles.fileName}>{fileName}</p>

      <ol className={styles.steps} aria-label="영상 등록 단계">
        {steps.map((step, index) => (
          <li
            aria-current={index === activeIndex ? 'step' : undefined}
            data-state={index < activeIndex ? 'done' : index === activeIndex ? 'active' : 'todo'}
            key={step.phase}
          >
            <span aria-hidden="true">{index < activeIndex ? <Check /> : index + 1}</span>
            {step.label}
          </li>
        ))}
      </ol>

      <div
        aria-label={view.phase === 'sending' ? '영상 전송 진행률' : '서버 확인 진행 중'}
        aria-valuemax={100}
        aria-valuemin={0}
        aria-valuenow={view.phase === 'sending' ? view.percent : undefined}
        className={styles.track}
        data-phase={view.phase}
        role="progressbar"
      >
        <span style={view.phase === 'sending' ? { width: `${view.percent}%` } : undefined} />
      </div>
      <p className={styles.detail} id="registration-upload-detail" role="status">
        {view.detail}
      </p>
      <p className={styles.note}>등록이 끝날 때까지 이 창을 닫거나 새로고침하지 마세요.</p>
    </dialog>
  );
}
