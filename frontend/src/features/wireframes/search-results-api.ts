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
import type { SearchResultDetails } from '@/features/wireframes/search-result-details';
import {
  getSearchShotTypeLabel,
  searchEvidenceFieldLabels,
  searchEvidenceSourceLabels,
} from '@/features/wireframes/search-result-labels';
import {
  formatMediaTime,
  formatSceneDuration,
  toScenePreviewMedia,
} from '@/features/wireframes/scene-preview-media';

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
  details: SearchResultDetails;
} {
  const reasons: Record<string, DegradedReason> = {
    resolver_fallback: 'resolver-fallback',
    dense_unavailable: 'dense-unavailable',
    snapshot_save_failed: 'snapshot-save-failed',
  };
  const guardReasons = {
    explicit_date_conflict: '명시한 날짜와 검증된 날짜가 일치하지 않음',
    approved_incident_conflict: '승인된 사건 충돌 규칙에 해당',
    approved_scene_exclusion: '승인된 장면 제외 규칙에 해당',
  } as const;
  const results = response.results.map((scene): SearchResult => {
    const displayName = scene.displayName ?? '제목 없는 영상';
    const evidence: SearchEvidenceMatch[] = scene.matchEvidence.map((item) => ({
      field: searchEvidenceFieldLabels[item.field],
      value: item.value ?? '근거 내용 기록 없음',
      source: searchEvidenceSourceLabels[item.source] ?? '정보 없음',
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
      displayName,
      title: scene.sceneDescription ?? displayName,
      clip: displayName,
      time: `${formatMediaTime(media.sceneStart)} – ${formatMediaTime(media.sceneEnd)}`,
      duration: formatSceneDuration((scene.endTimeMs - scene.startTimeMs) / 1000),
      totalDuration: '',
      totalSeconds: 0,
      broadcastDate: scene.broadcastDate.value,
      filmedDate: scene.filmedDate.value,
      filmingState: scene.filmedDate.verificationStatus,
      shotType: getSearchShotTypeLabel(scene.shotType),
      sceneType: scene.sceneType ?? '정보 없음',
      evidenceType: '화면 설명',
      evidence: primary.value,
      matchEvidence: primary,
      additionalEvidence: evidence.slice(1),
      matchedKeywords: scene.matchedKeywords,
      source: primary.source,
      score: 0,
    };
  });
  return {
    results,
    details: {
      resolverStatus: response.queryResolutionStatus === 'resolved' ? 'succeeded' : 'fallback',
      excludedCount: response.guardSummary.excludedResultCount,
      exclusionReasons: response.guardSummary.reasons.map((reason) => guardReasons[reason]),
    },
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
