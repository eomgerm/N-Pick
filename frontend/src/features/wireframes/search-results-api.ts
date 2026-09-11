import { fetchJson } from '@/lib/api/client';
import {
  parseSearchResponse,
  SEARCH_API_PATH,
  type SearchRequestBody,
  type SearchResponse,
} from '@/features/wireframes/search-api-contract';
import type { SearchResult, SearchEvidenceMatch } from '@/features/wireframes/demo-scenes';
import type {
  DegradedReason,
  SearchExecutionPresentation,
} from '@/features/wireframes/search-execution-status';
import { formatMediaTime, toScenePreviewMedia } from '@/features/wireframes/scene-preview-media';

export async function searchScenes(body: SearchRequestBody, signal?: AbortSignal) {
  return parseSearchResponse(
    await fetchJson<unknown>(SEARCH_API_PATH, {
      method: 'POST',
      body,
      signal,
    }),
  );
}

export function presentSearchResponse(response: SearchResponse): {
  results: SearchResult[];
  execution: SearchExecutionPresentation;
} {
  const reasons: Record<string, DegradedReason> = {
    resolver_fallback: 'resolver-fallback',
    dense_unavailable: 'dense-unavailable',
    snapshot_save_failed: 'snapshot-save-failed',
  };
  const fields = {
    caption: '장면 설명',
    ocr: '화면 속 글자 (OCR)',
    transcript: '대사',
    tag: '태그',
  } as const;
  const results = response.results.map((scene): SearchResult => {
    const evidence: SearchEvidenceMatch[] = scene.matchEvidence.map((item) => ({
      field: fields[item.field],
      value: item.value,
      source: item.source,
      status: item.verificationStatus,
    }));
    const primary = evidence[0] ?? {
      field: '장면 설명',
      value: '일치 근거 없음',
      source: '정보 없음',
      status: 'unknown',
    };
    const media = toScenePreviewMedia(scene);
    return {
      ...media,
      id: scene.rank,
      sceneId: scene.sceneId,
      searchResultId: scene.searchResultId,
      rank: scene.rank,
      displayName: scene.displayName,
      title: scene.sceneDescription ?? scene.displayName,
      clip: scene.displayName,
      time: `${formatMediaTime(media.sceneStart)} – ${formatMediaTime(media.sceneEnd)}`,
      duration: `${(scene.endTimeMs - scene.startTimeMs) / 1000}초`,
      totalDuration: '',
      totalSeconds: 0,
      broadcastDate: scene.broadcastDate.value,
      filmedDate: scene.filmedDate.value,
      filmingState: scene.filmedDate.verificationStatus,
      shotType: { anchor: '앵커', interview: '인터뷰', b_roll: '자료 화면', unknown: '정보 없음' }[
        scene.shotType
      ],
      sceneType: scene.sceneType ?? '정보 없음',
      evidenceType: '화면 설명',
      evidence: primary.value,
      matchEvidence: primary,
      additionalEvidence: evidence.slice(1),
      matchedKeywords: scene.matchedKeywords,
      source: primary.source,
      score: 0,
      imageClass: '',
      imageLabel: '대표 이미지 없음',
    };
  });
  return {
    results,
    execution:
      response.status === 'succeeded'
        ? {
            status: 'succeeded',
            degradedReasons: [],
            hasAppliedReviewRule: response.hasAppliedReviewRule,
          }
        : {
            status: 'degraded',
            degradedReasons: response.degradedReasons.map((reason) => reasons[reason]) as [
              DegradedReason,
              ...DegradedReason[],
            ],
            hasAppliedReviewRule: response.hasAppliedReviewRule,
          },
  };
}
