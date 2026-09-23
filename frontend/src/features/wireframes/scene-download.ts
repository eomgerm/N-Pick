const failureMessages: Record<string, string> = {
  CLIP_404_001: '원본 영상을 찾을 수 없습니다.',
  CLIP_404_002: '원본 영상 파일을 찾을 수 없습니다.',
  CLIP_404_003: '장면 정보를 찾을 수 없습니다.',
  CLIP_503_010: '영상 파일을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.',
  CLIP_503_011: '영상 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  CLIP_503_012: '다른 장면을 준비 중이거나 장면 추출에 실패했습니다. 잠시 후 다시 시도해 주세요.',
};

const defaultFailureMessage = '장면 영상을 다운로드하지 못했습니다. 잠시 후 다시 시도해 주세요.';
const defaultClipFailureMessage =
  '원본 클립을 다운로드하지 못했습니다. 잠시 후 다시 시도해 주세요.';

const clipFailureMessages: Record<number, string> = {
  401: '로그인이 만료되었습니다. 다시 로그인한 뒤 시도해 주세요.',
  403: '원본 클립을 다운로드할 권한이 없습니다.',
  404: '원본 영상 파일을 찾을 수 없습니다.',
  503: '영상 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
};

export class ClipDownloadError extends Error {
  constructor(message = defaultClipFailureMessage) {
    super(message);
    this.name = 'ClipDownloadError';
  }
}

export class SceneDownloadError extends Error {
  constructor(message = defaultFailureMessage) {
    super(message);
    this.name = 'SceneDownloadError';
  }
}

function safeFileName(value: string | null) {
  if (!value) return undefined;
  const leaf = value.split(/[\\/]/).at(-1) ?? '';
  const cleaned = leaf
    .replace(/[\r\n\0]/g, '')
    .trim()
    .replace(/^\.+/, '');
  return cleaned && cleaned.length <= 255 ? cleaned : undefined;
}

export function readDownloadFileName(header: string | null) {
  if (!header) return undefined;
  const encoded = header.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try {
      return safeFileName(decodeURIComponent(encoded.trim()));
    } catch {
      return undefined;
    }
  }
  return safeFileName(header.match(/filename\s*=\s*"([^"]+)"/i)?.[1] ?? null);
}

async function readFailureMessage(response: Response) {
  try {
    const payload: unknown = JSON.parse(await response.text());
    const code = (payload as { code?: unknown } | null)?.code;
    if (typeof code === 'string' && failureMessages[code]) return failureMessages[code];
  } catch {
    // 공통 실패 envelope가 아니어도 진단 본문을 화면에 노출하지 않는다.
  }
  return defaultFailureMessage;
}

export async function fetchSceneDownload(url: string, signal?: AbortSignal) {
  let response: Response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      credentials: 'include',
      headers: { accept: 'video/mp4' },
      redirect: 'error',
      signal,
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new SceneDownloadError();
  }
  if (!response.ok) throw new SceneDownloadError(await readFailureMessage(response));
  const blob = await response.blob();
  if (blob.size === 0) throw new SceneDownloadError();
  return {
    blob,
    fileName: readDownloadFileName(response.headers.get('content-disposition')),
  };
}

export async function checkClipDownload(url: string, signal?: AbortSignal) {
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'HEAD',
      cache: 'no-store',
      credentials: 'include',
      headers: { accept: 'video/mp4,video/quicktime' },
      redirect: 'error',
      signal,
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new ClipDownloadError();
  }
  if (!response.ok) {
    throw new ClipDownloadError(clipFailureMessages[response.status] ?? defaultClipFailureMessage);
  }
}

export function startClipDownload(url: string) {
  const anchor = document.createElement('a');
  anchor.download = '';
  anchor.href = url;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
}

export function saveSceneDownload(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.download = fileName;
  anchor.href = objectUrl;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
}
