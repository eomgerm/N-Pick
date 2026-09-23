import { ApiClientError, fetchJson } from '@/lib/api/client';
import { validateSearchQuery } from '@/features/wireframes/input-validation';
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
  const validationError = validateSearchQuery(body.query);
  if (validationError) throw new ApiClientError('api', 0, { message: validationError });
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

// 더보기로 이어 붙인 페이지들의 실행 상태는 하나로 합친다. 첫 페이지만 보면 이후 페이지에서 생긴
// 열화(snapshot 저장 실패·resolver fallback·dense 장애)가 화면에서 사라진다 (S15P21A501-251 리뷰).
// 한 페이지라도 열화면 전체를 열화로 보고, 사유는 합집합·검수 규칙 적용은 논리합으로 센다.
export function mergeSearchExecutions(
  executions: readonly SearchExecutionPresentation[],
): SearchExecutionPresentation {
  const reasons = new Set<DegradedReason>();
  let hasAppliedReviewRule = false;
  for (const execution of executions) {
    for (const reason of execution.degradedReasons) reasons.add(reason);
    if (execution.hasAppliedReviewRule) hasAppliedReviewRule = true;
  }
  if (reasons.size === 0) {
    return { status: 'succeeded', degradedReasons: [], hasAppliedReviewRule };
  }
  return {
    status: 'degraded',
    degradedReasons: [...reasons] as [DegradedReason, ...DegradedReason[]],
    hasAppliedReviewRule,
  };
}

// 상세도 페이지별로 다르다. resolver 는 한 페이지라도 fallback 이면 fallback으로 본다.
export function mergeSearchResultDetails(
  details: readonly SearchResultDetails[],
): SearchResultDetails {
  let resolverStatus: SearchResultDetails['resolverStatus'] = 'succeeded';
  for (const detail of details) {
    if (detail.resolverStatus === 'fallback') resolverStatus = 'fallback';
  }
  // 제외 수는 페이지마다 전체 후보 pool 기준으로 다시 세므로 합산하면 페이지 수만큼 부풀려진다
  // (S15P21A501-280 #1: 제외 4건이 2페이지면 8로 보이던 문제). 첫 페이지 값이 그 pool 기준
  // 대표값이다. 제외 사유도 같은 첫 페이지 기준으로 쓴다 — 수는 첫 페이지·사유는 합집합으로
  // 두면 「0건 + 사유 있음」 모순이 생겨 §5.1 불변식(수 0이면 사유도 빔)이 깨진다. guard 사유는
  // 페이지 무관 pool 전체 기준이라 페이지별로 달라지는 건 승인 장면 제외뿐이고, 그 페이지-구간
  // 몫은 offset 모델에선 정확히 합칠 수 없어 cursor 작업에서 완결한다 — 사유까지 첫 페이지로
  // 좁혀도 실질 손실 없이 모순만 사라진다.
  return {
    resolverStatus,
    excludedCount: details[0]?.excludedCount ?? 0,
    exclusionReasons: details[0]?.exclusionReasons ?? [],
  };
}
