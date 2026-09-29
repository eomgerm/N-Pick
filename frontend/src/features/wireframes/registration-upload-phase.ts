import { formatFileSize } from '@/features/wireframes/registration-files';

export interface UploadProgress {
  loaded: number;
  total: number;
}

export interface UploadPhaseView {
  /** sending: 브라우저가 파일을 보내는 중. verifying: 다 보냈고 서버 응답(형식·중복 검사)을 기다리는 중. */
  phase: 'sending' | 'verifying';
  percent: number;
  detail: string;
}

// 영상 등록 대기 화면의 단계 판정 (S15P21A501-325). 서버 검사 시간은 알 수 없으므로
// 전송이 끝난 뒤에는 퍼센트 대신 확인 중 단계로 넘긴다. 끝까지 보내기 전에는 100%를 보이지 않는다.
export function uploadPhaseView(
  progress: UploadProgress | null,
  fileSize: number,
): UploadPhaseView {
  const total = progress && progress.total > 0 ? progress.total : fileSize;
  const loaded = Math.min(progress?.loaded ?? 0, total);
  if (total > 0 && loaded >= total) {
    return { phase: 'verifying', percent: 100, detail: '영상 형식과 중복 여부를 확인하고 있어요.' };
  }
  const percent = total > 0 ? Math.min(99, Math.floor((loaded / total) * 100)) : 0;
  return {
    phase: 'sending',
    percent,
    detail: `${percent}% · ${formatFileSize(loaded)} / ${formatFileSize(total)}`,
  };
}
