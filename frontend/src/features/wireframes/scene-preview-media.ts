import { createApiUrl } from '@/lib/api/client';

export interface ScenePreviewMedia {
  clipId?: string;
  sceneStart: number;
  sceneEnd: number;
}

export function getSceneMediaUrl({ clipId, sceneStart, sceneEnd }: ScenePreviewMedia) {
  if (
    !clipId ||
    !/^[1-9]\d*$/.test(clipId) ||
    !Number.isFinite(sceneStart) ||
    !Number.isFinite(sceneEnd) ||
    sceneStart < 0 ||
    sceneEnd <= sceneStart
  )
    return null;
  return createApiUrl(`/media/${clipId}`);
}

export function toScenePreviewMedia(scene: {
  clipId: string;
  startTimeMs: number;
  endTimeMs: number;
}): ScenePreviewMedia {
  return {
    clipId: scene.clipId,
    sceneStart: scene.startTimeMs / 1000,
    sceneEnd: scene.endTimeMs / 1000,
  };
}

export function formatMediaTime(seconds: number) {
  const value = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(value / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  const time = `${String(minutes).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
  return hours ? `${hours}:${time}` : time;
}
