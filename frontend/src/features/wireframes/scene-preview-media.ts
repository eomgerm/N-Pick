import { createApiUrl } from '@/lib/api/client';
import { formatMediaTime } from '@/features/wireframes/media-time';

// 등록 화면처럼 API client 가 필요 없는 곳은 media-time 에서 바로 가져온다.
export { formatMediaTime };

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

function isPublicId(value: string | null | undefined) {
  return Boolean(value && /^[1-9]\d*$/.test(value));
}

export function getClipDownloadUrl(clipId: string | null | undefined) {
  if (!isPublicId(clipId)) return null;
  return createApiUrl(`/media/${clipId}/download`);
}

export function getSceneDownloadUrl(sceneId: string | number | null | undefined) {
  const value = sceneId === null || sceneId === undefined ? undefined : String(sceneId);
  if (!isPublicId(value)) return null;
  return createApiUrl(`/media/scenes/${value}/download`);
}

// 검색·문의 응답은 이미지도 URL도 싣지 않는다. scene_id로 endpoint를 조립한다(web-api.md §6.8).
export function getSceneThumbnailUrl(sceneId: string | null | undefined) {
  if (!isPublicId(sceneId)) return null;
  return createApiUrl(`/scenes/${sceneId}/thumbnail`);
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

export function formatSceneDuration(seconds: number) {
  return `${Math.max(0, Math.floor(seconds))}초`;
}
