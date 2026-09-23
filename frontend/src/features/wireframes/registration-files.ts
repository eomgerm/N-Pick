type FileInfo = Pick<File, 'name' | 'size' | 'type'>;

export const MAX_VIDEO_SIZE_BYTES = 10 * 1024 * 1024 * 1024;
export const MAX_SUBTITLE_SIZE_BYTES = 10 * 1024 * 1024;
export const videoAccept = 'video/mp4,video/quicktime,.mp4,.mov';
export const subtitleAccept = '.srt,.vtt,.json';
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
  if (file.size === 0) return '내용이 비어 있는 자막 파일은 등록할 수 없습니다.';
  if (file.size > MAX_SUBTITLE_SIZE_BYTES) return '자막 파일은 10 MiB 이하만 추가할 수 있어요.';
  if (!/\.(srt|vtt|json)$/i.test(file.name))
    return '자막 파일은 SRT, VTT 또는 승인된 JSON 형식으로 선택해 주세요.';
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

// 컨테이너의 헤더만 읽는다. 큰 mdat 본문은 건너뛰며 실제 디코딩·재생 검사는 서버가 맡는다.
export async function validateVideoContent(file: File): Promise<string> {
  const error = '파일 내용이 MP4 또는 MOV 영상 형식이 아닙니다. 원본 영상 파일을 선택해 주세요.';
  try {
    let offset = 0;
    let hasFileType = false;
    let hasMovie = false;
    let hasMedia = false;
    for (let atoms = 0; offset < file.size && atoms < 128; atoms++) {
      const bytes = new Uint8Array(await file.slice(offset, offset + 16).arrayBuffer());
      if (bytes.length < 8) return error;
      const view = new DataView(bytes.buffer);
      const type = String.fromCharCode(...bytes.subarray(4, 8));
      const shortSize = view.getUint32(0);
      if (shortSize === 1 && bytes.length < 16) return error;
      const size = shortSize === 1 ? Number(view.getBigUint64(8)) : shortSize || file.size - offset;
      const headerSize = shortSize === 1 ? 16 : 8;
      if (!Number.isSafeInteger(size) || size < headerSize || size > file.size - offset)
        return error;
      if (type === 'ftyp') {
        if (size < headerSize + 8 || size > 4096 || (size - headerSize) % 4 !== 0) return error;
        const body = new Uint8Array(
          await file.slice(offset + headerSize, offset + size).arrayBuffer(),
        );
        const brand = String.fromCharCode(...body.subarray(0, 4));
        const supported = /\.mov$/i.test(file.name)
          ? brand === 'qt  '
          : /^(isom|iso[2-9]|mp4[12]|avc1|M4V |M4VH|M4VP|MSNV|dash)$/.test(brand);
        if (!supported) return error;
        hasFileType = true;
      }
      if (type === 'moov' && size > headerSize) hasMovie = true;
      if (type === 'mdat' && size > headerSize) hasMedia = true;
      if (hasMovie && hasMedia && (hasFileType || /\.mov$/i.test(file.name))) return '';
      offset += size;
    }
    return error;
  } catch {
    return '영상 파일을 읽을 수 없습니다. 파일을 다시 선택해 주세요.';
  }
}

function hasBinaryCharacters(text: string): boolean {
  for (const character of text) {
    const code = character.charCodeAt(0);
    if ((code < 32 && ![9, 10, 12, 13].includes(code)) || code === 127) return true;
  }
  return false;
}

function hasDocumentHeader(text: string): boolean {
  return /^(%PDF-|%!PS|\{\\rtf)/.test(text.trimStart());
}

function isSubtitleCue(start: number, end: number, text: unknown): boolean {
  return (
    Number.isSafeInteger(start) &&
    start >= 0 &&
    Number.isSafeInteger(end) &&
    end > start &&
    typeof text === 'string' &&
    text.trim().length > 0
  );
}

function subtitleTime(value: string, vtt: boolean): number {
  const match = value.match(
    vtt ? /^(?:(\d{2,}):)?([0-5]\d):([0-5]\d)\.(\d{3})$/ : /^(\d{2,}):([0-5]\d):([0-5]\d),(\d{3})$/,
  );
  return match
    ? Number(match[1] ?? 0) * 3600000 +
        Number(match[2]) * 60000 +
        Number(match[3]) * 1000 +
        Number(match[4])
    : NaN;
}

function hasSubtitleStructure(text: string, extension: string): boolean {
  if (extension === 'json') {
    const value = JSON.parse(text);
    return (
      value !== null &&
      value.schemaVersion === 'npick.subtitle/v1' &&
      Array.isArray(value.segments) &&
      value.segments.length > 0 &&
      value.segments.every(
        (cue: { s?: number; e?: number; t?: unknown } | null) =>
          cue !== null && isSubtitleCue(cue.s ?? NaN, cue.e ?? NaN, cue.t),
      )
    );
  }
  const vtt = extension === 'vtt';
  const normalized = text.replace(/\r\n?/g, '\n');
  const blocks = (vtt ? normalized : normalized.replace(/^(?:[\t ]*\n)+/, ''))
    .trimEnd()
    .split(/\n(?:[\t ]*\n)+/)
    .filter((block) => block.trim());
  if (vtt) {
    const header = blocks.shift()?.split('\n');
    if (
      !header ||
      !/^WEBVTT(?:[ \t].*)?$/.test(header[0]) ||
      header.some((line) => line.includes('-->'))
    )
      return false;
  }
  let cues = 0;
  for (const block of blocks) {
    const lines = block.split('\n');
    if (vtt && /^(NOTE(?:[ \t].*)?|STYLE|REGION)$/.test(lines[0])) continue;
    const timing = lines[0].includes('-->') ? 0 : 1;
    if (!vtt && (timing !== 1 || !/^\d+$/.test(lines[0]))) return false;
    const match = lines[timing]?.match(/^(\S+)[ \t]+-->[ \t]+(\S+)(.*)$/);
    if (!match || (!vtt && match[3].trim())) return false;
    if (
      !isSubtitleCue(
        subtitleTime(match[1], vtt),
        subtitleTime(match[2], vtt),
        lines.slice(timing + 1).join('\n'),
      )
    )
      return false;
    cues++;
  }
  return cues > 0;
}

export async function validateSubtitleContent(file: File): Promise<string> {
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
    if (
      !hasBinaryCharacters(text) &&
      hasSubtitleStructure(text, file.name.split('.').at(-1)!.toLowerCase())
    )
      return '';
  } catch {
    // 읽기·인코딩·JSON 파싱 실패를 같은 입력 오류로 안내한다.
  }
  return '파일 내용이 올바른 자막 형식이 아닙니다. UTF-8 SRT, VTT 또는 승인된 JSON 파일을 선택해 주세요.';
}

export async function validateScriptContent(file: File): Promise<string> {
  // TXT에는 고유 형식 헤더가 없다. 전체 내용을 UTF-8로 검사하되 큰 파일을 한꺼번에 메모리에 올리지 않는다.
  try {
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let hasText = false;
    for (let offset = 0; offset < file.size; offset += 65536) {
      const end = Math.min(offset + 65536, file.size);
      const text = decoder.decode(await file.slice(offset, end).arrayBuffer(), {
        stream: end < file.size,
      });
      if (hasBinaryCharacters(text) || (offset === 0 && hasDocumentHeader(text))) {
        return '일반 대본은 문서·바이너리 파일이 아닌 UTF-8 TXT 파일을 선택해 주세요.';
      }
      hasText ||= text.trim().length > 0;
    }
    if (hasText) return '';
  } catch {
    // 파일을 읽지 못하거나 UTF-8이 아니면 선택을 완료하지 않는다.
  }
  return '일반 대본은 내용이 있는 UTF-8 TXT 파일을 선택해 주세요.';
}

export function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
