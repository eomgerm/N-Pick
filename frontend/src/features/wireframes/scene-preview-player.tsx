'use client';

import { useEffect, useRef, useState } from 'react';

import {
  formatMediaTime,
  getSceneMediaUrl,
  type ScenePreviewMedia,
} from '@/features/wireframes/scene-preview-media';

interface ScenePreviewPlayerProps extends ScenePreviewMedia {
  title: string;
  autoPlay?: boolean;
}

export function ScenePreviewPlayer(props: ScenePreviewPlayerProps) {
  const [attempt, setAttempt] = useState(0);
  const src = getSceneMediaUrl(props);
  if (!src) {
    return <p role="status">영상 ID 또는 장면 구간을 확인할 수 없어 재생할 수 없습니다.</p>;
  }
  return (
    <MediaPlayer
      {...props}
      src={src}
      key={`${src}:${props.sceneStart}:${props.sceneEnd}:${attempt}`}
      onRetry={() => setAttempt((value) => value + 1)}
    />
  );
}

function MediaPlayer({
  src,
  sceneStart,
  sceneEnd,
  title,
  autoPlay = true,
  onRetry,
}: ScenePreviewPlayerProps & { src: string; onRetry: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const replayRef = useRef<() => void>(() => {});
  const [currentTime, setCurrentTime] = useState(sceneStart);
  const [duration, setDuration] = useState<number | null>(null);
  const [isReady, setIsReady] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isBuffering, setIsBuffering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const video = videoRef.current!;
    let isDisposed = false;
    let hasInitialized = false;
    let hasFailed = false;
    let shouldPlayAfterSeek = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const listeners: Array<[string, EventListener]> = [];
    function listen(name: string, handler: () => void) {
      video.addEventListener(name, handler);
      listeners.push([name, handler]);
    }
    function fail(message: string) {
      if (isDisposed) return;
      hasFailed = true;
      shouldPlayAfterSeek = false;
      clearTimeout(timer);
      video.pause();
      setIsBuffering(false);
      setError(message);
    }
    function deadline(message: string) {
      clearTimeout(timer);
      timer = setTimeout(() => fail(message), 20_000);
    }
    function play() {
      void video.play().catch((reason: unknown) => {
        if (isDisposed || hasFailed) return;
        if (reason instanceof DOMException && reason.name === 'AbortError') return;
        setNotice('자동으로 재생하지 못했습니다. 영상의 재생 버튼을 눌러 주세요.');
      });
    }
    function ready() {
      if (hasFailed) return;
      clearTimeout(timer);
      hasInitialized = true;
      setIsReady(true);
      setIsBuffering(false);
      setCurrentTime(video.currentTime);
      if (shouldPlayAfterSeek) {
        shouldPlayAfterSeek = false;
        play();
      }
    }
    function seekStart(shouldPlay: boolean) {
      shouldPlayAfterSeek = shouldPlay;
      setNotice(null);
      // A seek to the current position (especially zero) need not emit seeked.
      if (Math.abs(video.currentTime - sceneStart) < 0.001 && !video.seeking) {
        ready();
        return;
      }
      deadline('장면 위치로 이동하지 못했습니다. 다시 시도해 주세요.');
      try {
        video.currentTime = sceneStart;
      } catch {
        fail('장면 위치로 이동하지 못했습니다. 다시 시도해 주세요.');
      }
    }
    listen('loadedmetadata', () => {
      if (hasFailed) return;
      if (
        !Number.isFinite(video.duration) ||
        sceneStart >= video.duration ||
        sceneEnd > video.duration + 0.05
      ) {
        fail('장면 구간이 원본 영상 길이와 맞지 않아 재생할 수 없습니다.');
        return;
      }
      setDuration(video.duration);
      seekStart(autoPlay);
    });
    listen('seeked', ready);
    listen('seeking', () => {
      if (hasFailed) return;
      setIsBuffering(true);
      deadline('영상 위치로 이동하지 못했습니다. 다시 시도해 주세요.');
    });
    listen('timeupdate', () => {
      if (hasInitialized && !hasFailed) setCurrentTime(video.currentTime);
    });
    listen('playing', () => {
      if (hasFailed) {
        video.pause();
        return;
      }
      clearTimeout(timer);
      setIsPlaying(true);
      setIsBuffering(false);
      setNotice(null);
    });
    listen('pause', () => setIsPlaying(false));
    listen('ended', () => setIsPlaying(false));
    listen('waiting', () => {
      if (!hasInitialized || hasFailed) return;
      setIsBuffering(true);
      deadline('영상을 불러오지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    });
    listen('canplay', () => {
      if (hasInitialized && !video.seeking && !hasFailed) {
        clearTimeout(timer);
        setIsBuffering(false);
      }
    });
    listen('error', () =>
      fail(
        '영상을 불러오거나 재생할 수 없습니다. 파일 또는 연결 상태를 확인하고 다시 시도해 주세요.',
      ),
    );
    replayRef.current = () => {
      if (!hasFailed) seekStart(true);
    };
    deadline('영상을 불러오지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    video.src = src;
    video.load();
    return () => {
      isDisposed = true;
      clearTimeout(timer);
      listeners.forEach(([name, handler]) => video.removeEventListener(name, handler));
      replayRef.current = () => {};
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
  }, [src, sceneStart, sceneEnd, autoPlay]);

  return (
    <div
      className="space-y-3"
      data-preview-state={error ? 'error' : !isReady ? 'loading' : isPlaying ? 'playing' : 'paused'}
    >
      <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
        <video
          aria-label={`${title} 원본 영상`}
          className={`h-full w-full ${!isReady || error ? 'invisible' : ''}`}
          controls={isReady && !error}
          crossOrigin="use-credentials"
          playsInline
          preload="metadata"
          ref={videoRef}
        />
        {!isReady && !error ? (
          <p role="status" className="absolute inset-0 grid place-content-center text-white">
            장면을 불러오고 있어요
          </p>
        ) : null}
        {error ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-5 text-center text-white">
            <p role="alert">{error}</p>
            <button
              className="rounded-lg border px-4 py-2 focus-visible:outline-2"
              onClick={onRetry}
              type="button"
            >
              다시 시도
            </button>
          </div>
        ) : null}
      </div>
      <div className="flex flex-wrap justify-between gap-2 text-sm">
        <span>
          현재 위치 {formatMediaTime(currentTime)} /{' '}
          {duration === null ? '길이 확인 중' : formatMediaTime(duration)}
        </span>
        <span>
          IN {formatMediaTime(sceneStart)} · OUT {formatMediaTime(sceneEnd)}
        </span>
      </div>
      {duration !== null ? (
        <div
          aria-label="전체 영상 안의 선택 구간과 현재 위치"
          className="relative h-3 overflow-hidden rounded-full bg-(--line)"
          role="img"
        >
          <span
            className="absolute top-0 h-full bg-blue-500/50"
            style={{
              left: `${(sceneStart / duration) * 100}%`,
              width: `${((sceneEnd - sceneStart) / duration) * 100}%`,
            }}
          />
          <span
            className="absolute top-0 h-full w-0.5 bg-blue-700"
            style={{ left: `${Math.min(99.8, (currentTime / duration) * 100)}%` }}
          />
        </div>
      ) : null}
      <p className="text-sm text-(--muted)">선택 구간이 끝나도 원본 영상은 계속 재생됩니다.</p>
      <button
        className="rounded-lg border border-(--line) px-4 py-2 text-sm focus-visible:outline-2 disabled:opacity-50"
        disabled={!isReady || Boolean(error)}
        onClick={() => replayRef.current()}
        type="button"
      >
        구간 다시 재생
      </button>
      <p role="status" className="text-sm">
        {error
          ? ''
          : (notice ??
            (isBuffering
              ? '영상 위치를 준비하고 있어요.'
              : isReady
                ? isPlaying
                  ? '재생 중'
                  : '일시 정지'
                : ''))}
      </p>
    </div>
  );
}
