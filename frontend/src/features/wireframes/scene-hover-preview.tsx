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
    const toSceneStart = () => {
      video.currentTime = sceneStart;
    };
    const onSeeked = () => {
      setIsReady(true);
      // Reduced motion keeps the first frame of the scene instead of playing it.
      if (!isStill) void video.play().catch(() => {});
    };
    const onTimeUpdate = () => {
      if (video.currentTime >= sceneEnd) toSceneStart();
    };
    video.addEventListener('loadedmetadata', toSceneStart);
    video.addEventListener('seeked', onSeeked);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.muted = true;
    video.src = src;
    video.load();
    return () => {
      video.removeEventListener('loadedmetadata', toSceneStart);
      video.removeEventListener('seeked', onSeeked);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.pause();
      video.removeAttribute('src');
      video.load();
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
