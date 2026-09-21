'use client';

import { ImageOff, LoaderCircle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

/**
 * Shows the scene's representative keyframe on a result card.
 *
 * The endpoint returns the keyframe at its original resolution, about 420 KiB each
 * (web-api.md §6.8), so a screen of ten cards is a 4 MB burst. The card only asks for
 * bytes once it nears the viewport, and at most a few cards fetch at a time.
 */
// ponytail: 고정 상한. 회선별로 조절할 이유가 생기면 그때 가변으로 바꾼다.
const MAX_CONCURRENT_REQUESTS = 4;

const failureMessages: Record<string, string> = {
  SCENE_404_001: '장면 정보를 찾을 수 없습니다.',
  SCENE_404_002: '대표 이미지를 아직 준비하고 있습니다.',
  SCENE_404_003: '대표 이미지 파일을 찾을 수 없습니다.',
};
const defaultFailureMessage = '대표 이미지를 불러오지 못했습니다.';

let activeRequests = 0;
const waitingRequests: Array<() => void> = [];

function acquireSlot(): Promise<void> {
  if (activeRequests < MAX_CONCURRENT_REQUESTS) {
    activeRequests += 1;
    return Promise.resolve();
  }
  return new Promise((resolve) => waitingRequests.push(resolve));
}

function releaseSlot() {
  // 대기 중인 요청이 있으면 자리를 그대로 넘긴다.
  const next = waitingRequests.shift();
  if (next) next();
  else activeRequests -= 1;
}

async function readFailureMessage(response: Response) {
  try {
    const payload: unknown = JSON.parse(await response.text());
    const code = (payload as { code?: unknown } | null)?.code;
    if (typeof code === 'string' && failureMessages[code]) return failureMessages[code];
  } catch {
    // 실패 envelope가 아니어도 사용자에게 줄 안내는 같다.
  }
  return defaultFailureMessage;
}

export async function loadSceneThumbnail(
  src: string,
  signal: AbortSignal,
): Promise<{ objectUrl: string } | { message: string }> {
  await acquireSlot();
  try {
    if (signal.aborted) return { message: defaultFailureMessage };
    // 공통 client는 JSON envelope 전용이다. 성공 응답은 이미지 byte이므로 직접 받는다.
    // 기본 cache mode라야 `private, no-cache` + ETag 재검증(304)이 그대로 동작한다.
    const response = await fetch(src, {
      credentials: 'include',
      headers: { accept: 'image/*' },
      redirect: 'error',
      signal,
    });
    if (!response.ok) return { message: await readFailureMessage(response) };
    return { objectUrl: URL.createObjectURL(await response.blob()) };
  } catch {
    return { message: defaultFailureMessage };
  } finally {
    releaseSlot();
  }
}

interface SceneThumbnailProps {
  alt: string;
  src: string;
}

type LoadedThumbnail = { src: string } & ({ objectUrl: string } | { message: string });

export function SceneThumbnail({ alt, src }: SceneThumbnailProps) {
  const containerRef = useRef<HTMLSpanElement>(null);
  const [isNearViewport, setIsNearViewport] = useState(false);
  // 결과가 바뀌어 src가 달라지면 이전 장면의 결과는 그대로 버린다.
  const [loaded, setLoaded] = useState<LoadedThumbnail | null>(null);
  const current = loaded?.src === src ? loaded : null;
  const objectUrl = current && 'objectUrl' in current ? current.objectUrl : null;
  const message = current && 'message' in current ? current.message : null;

  useEffect(() => {
    const container = containerRef.current;
    if (!container || typeof IntersectionObserver === 'undefined') {
      setIsNearViewport(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        setIsNearViewport(true);
      },
      { rootMargin: '300px' },
    );
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!isNearViewport) return;
    const controller = new AbortController();
    let loadedUrl: string | undefined;
    void loadSceneThumbnail(src, controller.signal).then((result) => {
      if (controller.signal.aborted) {
        if ('objectUrl' in result) URL.revokeObjectURL(result.objectUrl);
        return;
      }
      if ('objectUrl' in result) loadedUrl = result.objectUrl;
      setLoaded({ src, ...result });
    });
    return () => {
      controller.abort();
      if (loadedUrl) URL.revokeObjectURL(loadedUrl);
    };
  }, [isNearViewport, src]);

  return (
    <span
      className="absolute inset-0 block overflow-hidden bg-[#17243b]"
      data-thumbnail-state={objectUrl ? 'ready' : message ? 'failed' : 'loading'}
      ref={containerRef}
    >
      {objectUrl ? (
        // 원본 byte를 blob으로 받아 표시하므로 next/image의 최적화 대상이 아니다.
        // eslint-disable-next-line @next/next/no-img-element
        <img alt={alt} className="h-full w-full object-cover" src={objectUrl} />
      ) : (
        <span className="absolute inset-0 flex flex-col items-center justify-center gap-2 px-3 text-center text-xs leading-relaxed [word-break:keep-all] text-[#c8d6e5]">
          {message ? (
            <>
              <ImageOff aria-hidden="true" className="size-6 shrink-0 text-[#9fb7ca]" />
              {message}
            </>
          ) : (
            <LoaderCircle
              aria-hidden="true"
              className="size-6 animate-spin text-[#9fb7ca] motion-reduce:animate-none"
            />
          )}
        </span>
      )}
    </span>
  );
}
