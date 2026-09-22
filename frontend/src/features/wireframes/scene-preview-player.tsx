'use client';

import { useEffect, useRef, useState } from 'react';
import { CircleAlert, Film, LoaderCircle, Repeat2, RotateCcw } from 'lucide-react';

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
    return (
      <div
        className="grid aspect-video min-h-52 w-full place-content-center justify-items-center gap-4 rounded-2xl bg-[#142330] p-6 text-center text-[#dbe7f0]"
        role="status"
      >
        <Film aria-hidden="true" className="size-9 text-[#9fb7ca]" />
        <p className="max-w-sm text-sm leading-relaxed [word-break:keep-all]">
          영상 ID 또는 장면 구간을 확인할 수 없어 재생할 수 없습니다.
        </p>
      </div>
    );
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
  const normalizePlaybackRef = useRef<() => void>(() => {});
  const isLoopingRef = useRef(false);
  const [isLooping, setIsLooping] = useState(false);
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
    let wasPlayingBeforeEnd = false;
    let shouldPlayAfterSeek = false;
    let loopFrame: number | null = null;
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
    function normalizePlayback() {
      if (!hasInitialized || hasFailed || !isLoopingRef.current || video.paused || video.seeking) {
        return;
      }
      if (video.currentTime < sceneStart || video.currentTime >= sceneEnd) {
        // Seeking while playing preserves playback and never queues a later resume.
        seekStart(false);
      }
    }
    function watchLoop() {
      loopFrame = null;
      if (!hasInitialized || hasFailed || !isLoopingRef.current || video.paused) return;
      normalizePlayback();
      loopFrame = requestAnimationFrame(watchLoop);
    }
    function syncLoopMonitor() {
      if (loopFrame !== null) cancelAnimationFrame(loopFrame);
      watchLoop();
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
    listen('seeked', () => {
      ready();
      normalizePlayback();
    });
    listen('seeking', () => {
      if (hasFailed) return;
      setIsBuffering(true);
      deadline('영상 위치로 이동하지 못했습니다. 다시 시도해 주세요.');
    });
    listen('timeupdate', () => {
      if (hasInitialized && !hasFailed) {
        normalizePlayback();
        setCurrentTime(video.currentTime);
      }
    });
    listen('play', normalizePlayback);
    listen('playing', () => {
      if (hasFailed) {
        video.pause();
        return;
      }
      clearTimeout(timer);
      wasPlayingBeforeEnd = true;
      setIsPlaying(true);
      setIsBuffering(false);
      setNotice(null);
      syncLoopMonitor();
    });
    listen('pause', () => {
      shouldPlayAfterSeek = false;
      // Natural completion emits pause before ended; a manual pause cancels looping.
      if (!video.ended) wasPlayingBeforeEnd = false;
      setIsPlaying(false);
      syncLoopMonitor();
    });
    listen('ended', () => {
      const shouldRepeat = wasPlayingBeforeEnd;
      wasPlayingBeforeEnd = false;
      setIsPlaying(false);
      if (hasInitialized && !hasFailed && isLoopingRef.current && shouldRepeat && video.ended) {
        seekStart(true);
      }
    });
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
    normalizePlaybackRef.current = syncLoopMonitor;
    deadline('영상을 불러오지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    video.src = src;
    video.load();
    return () => {
      isDisposed = true;
      clearTimeout(timer);
      if (loopFrame !== null) cancelAnimationFrame(loopFrame);
      listeners.forEach(([name, handler]) => video.removeEventListener(name, handler));
      replayRef.current = () => {};
      normalizePlaybackRef.current = () => {};
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
  }, [src, sceneStart, sceneEnd, autoPlay]);

  function handleLoopToggle() {
    const nextIsLooping = !isLoopingRef.current;
    isLoopingRef.current = nextIsLooping;
    setIsLooping(nextIsLooping);
    normalizePlaybackRef.current();
  }

  return (
    <div
      className="space-y-4"
      data-preview-state={error ? 'error' : !isReady ? 'loading' : isPlaying ? 'playing' : 'paused'}
    >
      <div className="relative aspect-video max-h-[min(50dvh,480px)] w-full overflow-hidden rounded-2xl bg-[#142330]">
        <video
          aria-label={`${title} 원본 영상`}
          className={`h-full w-full object-contain ${!isReady || error ? 'invisible' : ''}`}
          controls={isReady && !error}
          crossOrigin="use-credentials"
          playsInline
          preload="metadata"
          ref={videoRef}
        />
        {!isReady && !error ? (
          <div className="absolute inset-0 grid place-content-center justify-items-center gap-4 p-5 text-[#dbe7f0]">
            <LoaderCircle
              aria-hidden="true"
              className="size-7 animate-spin motion-reduce:animate-none"
            />
            <p role="status" className="text-sm">
              장면을 불러오고 있어요
            </p>
          </div>
        ) : null}
        {error ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-5 text-center text-[#dbe7f0]">
            <CircleAlert aria-hidden="true" className="size-8 shrink-0 text-[#f3d4a5]" />
            <p role="alert" className="max-w-md text-sm leading-relaxed [word-break:keep-all]">
              {error}
            </p>
            <button
              className="inline-flex min-h-10 items-center gap-2 rounded-xl bg-[#0756c6] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#06459d] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-white active:bg-[#05377e] motion-reduce:transition-none"
              onClick={onRetry}
              type="button"
            >
              <RotateCcw aria-hidden="true" className="size-4" />
              다시 시도
            </button>
          </div>
        ) : null}
      </div>
      <div className="rounded-2xl bg-white/40 p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-xs text-[#516477]">
          <span>
            현재 위치{' '}
            <strong className="ml-1 font-mono text-sm font-semibold text-[#17243b]">
              {formatMediaTime(currentTime)}
            </strong>{' '}
            /{' '}
            <span className="font-mono tabular-nums">
              {duration === null ? '길이 확인 중' : formatMediaTime(duration)}
            </span>
          </span>
          <span className="rounded-md bg-white/50 px-2 py-1 font-mono text-[#344c64] tabular-nums">
            IN {formatMediaTime(sceneStart)} · OUT {formatMediaTime(sceneEnd)}
          </span>
        </div>
        {duration !== null ? (
          <div
            aria-label="전체 영상 안의 선택 구간과 현재 위치"
            className="relative mt-3 h-2.5 overflow-hidden rounded-full bg-[#17243b]/10"
            role="img"
          >
            <span
              className="absolute top-0 h-full bg-[#1473e6]/40"
              style={{
                left: `${(sceneStart / duration) * 100}%`,
                width: `${((sceneEnd - sceneStart) / duration) * 100}%`,
              }}
            />
            <span
              className="absolute top-0 h-full w-0.5 bg-[#0756c6]"
              style={{ left: `${Math.min(99.8, (currentTime / duration) * 100)}%` }}
            />
          </div>
        ) : null}
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <button
              className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-[#0756c6] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#06459d] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[#0756c6] active:bg-[#05377e] disabled:cursor-not-allowed disabled:opacity-45 motion-reduce:transition-none"
              disabled={!isReady || Boolean(error)}
              onClick={() => replayRef.current()}
              type="button"
            >
              <RotateCcw aria-hidden="true" className="size-4" />
              구간 다시 재생
            </button>
            <button
              aria-label="구간 반복"
              aria-pressed={isLooping}
              className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-[#a6bacf] bg-white/70 px-4 py-2 text-sm font-semibold text-[#344c64] transition-colors hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[#0756c6] disabled:cursor-not-allowed disabled:opacity-45 aria-pressed:border-[#0756c6] aria-pressed:bg-[#e6f0ff] aria-pressed:text-[#0756c6] motion-reduce:transition-none"
              disabled={!isReady || Boolean(error)}
              onClick={handleLoopToggle}
              type="button"
            >
              <Repeat2 aria-hidden="true" className="size-4" />
              구간 반복 {isLooping ? '켜짐' : '꺼짐'}
            </button>
          </div>
          <p role="status" className="text-xs leading-relaxed text-[#516477]">
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
        <p className="mt-3 text-xs leading-relaxed [word-break:keep-all] text-[#516477]">
          {isLooping
            ? '선택 구간의 끝에서 시작으로 돌아가 반복 재생합니다.'
            : '선택 구간이 끝나도 원본 영상은 계속 재생됩니다.'}
        </p>
      </div>
    </div>
  );
}
