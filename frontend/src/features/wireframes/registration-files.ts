type FileInfo = Pick<File, 'name' | 'size' | 'type'>;

export const videoAccept = 'video/*,.mp4,.mov,.m4v,.mkv,.webm,.avi,.mpeg,.mpg,.ts,.mxf';
export const attachmentAccept = '.txt,.srt,.vtt';

export function validateVideoFiles(files: readonly FileInfo[]): string {
  if (files.length !== 1) return '영상 파일을 한 개 선택해 주세요.';
  const file = files[0];
  if (file.size === 0) return '비어 있는 파일은 등록할 수 없어요.';
  if (
    !file.type.startsWith('video/') &&
    !/\.(mp4|mov|m4v|mkv|webm|avi|mpeg|mpg|ts|mxf)$/i.test(file.name)
  ) {
    return '영상 파일을 선택해 주세요. 대본과 자막은 첨부 파일에 추가할 수 있어요.';
  }
  return '';
}

export function validateAttachmentFiles(files: readonly FileInfo[]): string {
  if (files.some((file) => !/\.(txt|srt|vtt)$/i.test(file.name))) {
    return '첨부 파일은 TXT, SRT, VTT 형식으로 추가해 주세요.';
  }
  if (files.some((file) => file.size === 0)) return '비어 있는 첨부 파일은 추가할 수 없어요.';
  return '';
}

export function mergeAttachments<T extends Pick<File, 'name' | 'size' | 'lastModified'>>(
  current: readonly T[],
  incoming: readonly T[],
): T[] {
  const merged = [...current];
  for (const file of incoming) {
    if (
      !merged.some(
        (item) =>
          item.name === file.name &&
          item.size === file.size &&
          item.lastModified === file.lastModified,
      )
    ) {
      merged.push(file);
    }
  }
  return merged;
}

export function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}
