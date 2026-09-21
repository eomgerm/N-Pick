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
  const fields = {
    caption: '장면 설명',
    ocr: '화면 속 글자 (OCR)',
    transcript: '대사',
    tag: '태그',
  } as const;
  // `source`는 계약이 어휘를 닫지 않은 비어 있지 않은 string이다(web-api.md §6.7). 서버가 실제로
  // 싣는 값만 옮기고, 그 밖의 값은 영어 원값 대신 대체 문구로 보여 준다.
  const sources: Record<string, string> = {
    scene_caption: 'AI 장면 설명',
    scene_transcript: '원본 대사',
    keyframe_ocr: '대표 이미지 글자 인식',
    dense_similarity: 'AI 의미 검색',
    user_input: '사용자 입력',
    original_metadata: '영상 원본 정보',
    cc: '방송 자막',
    ocr: '화면 글자 인식',
    asr: '음성 인식',
    vlm: 'AI 화면 분석',
    rule: '텍스트 자동 추출',
    reviewer_feedback: '아카이빙 팀 피드백',
  };
  const guardReasons = {
    explicit_date_conflict: '명시한 날짜와 검증된 날짜가 일치하지 않음',
    approved_incident_conflict: '승인된 사건 충돌 규칙에 해당',
    approved_scene_exclusion: '승인된 장면 제외 규칙에 해당',
  } as const;
  const results = response.results.map((scene): SearchResult => {
    const displayName = scene.displayName ?? '제목 없는 영상';
    const evidence: SearchEvidenceMatch[] = scene.matchEvidence.map((item) => ({
      field: fields[item.field],
      value: item.value ?? '근거 내용 기록 없음',
      source: sources[item.source] ?? '정보 없음',
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

// 상세도 페이지별로 다르다. resolver 는 한 페이지라도 fallback 이면 fallback, 제외 수는 지금까지
// 불러온 페이지 몫의 합, 제외 사유는 순서를 지키며 합집합으로 모은다.
export function mergeSearchResultDetails(
  details: readonly SearchResultDetails[],
): SearchResultDetails {
  const exclusionReasons: string[] = [];
  let excludedCount = 0;
  let resolverStatus: SearchResultDetails['resolverStatus'] = 'succeeded';
  for (const detail of details) {
    if (detail.resolverStatus === 'fallback') resolverStatus = 'fallback';
    excludedCount += detail.excludedCount ?? 0;
    for (const reason of detail.exclusionReasons ?? []) {
      if (!exclusionReasons.includes(reason)) exclusionReasons.push(reason);
    }
  }
  return { resolverStatus, excludedCount, exclusionReasons };
}
