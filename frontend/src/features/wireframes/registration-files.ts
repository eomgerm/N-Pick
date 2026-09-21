type FileInfo = Pick<File, 'name' | 'size' | 'type'>;

export const MAX_VIDEO_SIZE_BYTES = 10 * 1024 * 1024 * 1024;
export const videoAccept = 'video/mp4,video/quicktime,.mp4,.mov';
export const subtitleAccept = '.srt,.vtt';
export const scriptAccept = 'text/plain,.txt';

export function validateVideoFiles(files: readonly FileInfo[]): string {
  if (files.length !== 1) return '영상 파일을 선택해주세요.';
  const file = files[0];
  if (file.size === 0) return '올바른 영상 파일이 아닙니다.';
  if (file.size > MAX_VIDEO_SIZE_BYTES) return '영상 파일은 10 GiB 이하만 등록할 수 있어요.';

  const hasSupportedName = /\.(mp4|mov)$/i.test(file.name);
  const mediaType = file.type.split(';', 1)[0].trim().toLowerCase();
  const hasSupportedType =
    !mediaType || mediaType.startsWith('video/') || mediaType === 'application/octet-stream';
  if (!hasSupportedName || !hasSupportedType) {
    return '영상 파일은 MP4 또는 MOV 형식으로 선택해 주세요.';
  }
  return '';
}

export function validateSubtitleFiles(files: readonly FileInfo[]): string {
  if (files.length > 1) return '자막 파일은 하나만 선택할 수 있습니다.';
  if (files.length === 0) return '';
  const file = files[0];
  if (file.size === 0) return '올바른 자막 파일이 아닙니다.';
  if (!/\.(srt|vtt)$/i.test(file.name)) return '자막 파일은 SRT 또는 VTT 형식으로 선택해 주세요.';
  return '';
}

export function validateScriptFiles(files: readonly FileInfo[]): string {
  if (files.length > 1) return '일반 대본 파일은 하나만 선택할 수 있습니다.';
  if (files.length === 0) return '';
  const file = files[0];
  if (file.size === 0) return '내용이 비어 있는 대본 파일은 등록할 수 없습니다.';
  if (!/\.txt$/i.test(file.name)) return '일반 대본 파일은 TXT 형식으로 선택해 주세요.';
  return '';
}

export function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
