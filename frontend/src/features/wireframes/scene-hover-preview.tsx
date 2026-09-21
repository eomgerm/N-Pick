'use client';

import { useEffect, useRef, useState } from 'react';

import {
  getSceneMediaUrl,
  type ScenePreviewMedia,
} from '@/features/wireframes/scene-preview-media';
import styles from '@/features/wireframes/wireframe.module.css';

/**
 * Plays the scene segment of the original clip over the card thumbnail.
 *
 * The card mounts this only while it is hovered or holds focus, so at most one card
 * ever holds a transfer. Until the segment is on screen the element stays transparent
 * and the static thumbnail shows through, which also covers a failed or missing clip.
 */
export function SceneHoverPreview({ clipId, sceneStart, sceneEnd }: ScenePreviewMedia) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [isReady, setIsReady] = useState(false);
  const src = getSceneMediaUrl({ clipId, sceneStart, sceneEnd });

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !src) return;
    const isStill = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const onReady = () => {
      setIsReady(true);
      // Reduced motion keeps the first frame of the scene instead of playing it.
      if (!isStill) void video.play().catch(() => {});
    };
    const toSceneStart = () => {
      // A seek to the current position (especially zero) need not emit seeked.
      if (Math.abs(video.currentTime - sceneStart) < 0.001 && !video.seeking) {
        onReady();
        return;
      }
      video.currentTime = sceneStart;
    };
    const onTimeUpdate = () => {
      if (video.currentTime >= sceneEnd) toSceneStart();
    };
    video.addEventListener('loadedmetadata', toSceneStart);
    video.addEventListener('seeked', onReady);
    video.addEventListener('timeupdate', onTimeUpdate);
    // A scene that runs past the stored duration never reaches sceneEnd, so loop on the clip end too.
    video.addEventListener('ended', toSceneStart);
    video.muted = true;
    video.src = src;
    video.load();
    return () => {
      video.removeEventListener('loadedmetadata', toSceneStart);
      video.removeEventListener('seeked', onReady);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('ended', toSceneStart);
      video.pause();
      video.removeAttribute('src');
      video.load();
      setIsReady(false);
    };
  }, [src, sceneStart, sceneEnd]);

  if (!src) return null;
  return (
    <video
      aria-hidden="true"
      className={styles.hoverPreview}
      crossOrigin="use-credentials"
      data-ready={isReady ? '' : undefined}
      muted
      playsInline
      preload="metadata"
      ref={videoRef}
    />
  );
}
