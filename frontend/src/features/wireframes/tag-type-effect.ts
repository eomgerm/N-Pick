import type { ReviewTagType } from '@/features/wireframes/review-inquiry-api';

export const tagTypeLabels: Record<ReviewTagType, string> = {
  person: '인물',
  organization: '조직',
  location: '장소',
  facility: '시설',
  keyword: '키워드',
  event: '사건',
  season: '계절',
  weather: '날씨',
  scene_type: '장면 유형',
  filmed_date: '촬영일',
  broadcast_date: '방송일',
};

/**
 * 태그 유형이 검색 결과에 주는 효과 (S15P21A501-317, "기본값 없음 + 유형별 효과 표시").
 * 근거(BE): 구조 채널은 EVENT·PERSON·ORG·LOCATION·FACILITY·SCENE_TYPE 만 읽고
 * (StructuredScoreCalculator), 계절·날씨는 동점 정렬에만 쓰이며(SoftRankingService),
 * 날짜는 질의에 날짜 구간이 있을 때 구조 점수 축(BROADCAST_DATE·FILMED_DATE)으로 반영되지만 후보
 * 검색 조건에서는 빠져 태그만으로 새 후보를 끌어오지 못하고, 필터·F-06 오탐 가드에도 쓰인다.
 * 키워드는 어느 경로에서도 읽지 않는다.
 */
export type TagTypeEffect = 'search' | 'date-query' | 'rank-minor' | 'none';

export const tagTypeEffectGroups: ReadonlyArray<{
  effect: TagTypeEffect;
  label: string;
  hint: string;
  types: readonly ReviewTagType[];
}> = [
  {
    effect: 'search',
    label: '검색 반영',
    hint: '검색어와 일치하면 이 장면이 검색 결과에 반영됩니다.',
    types: ['event', 'person', 'organization', 'location', 'facility', 'scene_type'],
  },
  {
    effect: 'date-query',
    label: '날짜가 있는 검색에 반영',
    hint: '질의에 날짜가 있으면 그 날짜와 맞을 때 점수에 반영됩니다(태그만으로 새 후보가 되지는 않음). 날짜 필터·오탐 제외 판단에도 쓰입니다.',
    types: ['broadcast_date', 'filmed_date'],
  },
  {
    effect: 'rank-minor',
    label: '순위 영향 미미',
    hint: '점수가 같은 결과의 순서를 정할 때만 쓰여 순위 영향이 미미합니다.',
    types: ['season', 'weather'],
  },
  {
    effect: 'none',
    label: '검색 결과에 영향 없음',
    hint: '이 유형은 현재 검색 결과에 영향을 주지 않습니다.',
    types: ['keyword'],
  },
];

export function tagTypeEffect(tagType: ReviewTagType): TagTypeEffect {
  return tagTypeEffectGroups.find((group) => group.types.includes(tagType))!.effect;
}

export function tagTypeEffectHint(tagType: ReviewTagType): string {
  return tagTypeEffectGroups.find((group) => group.types.includes(tagType))!.hint;
}

/** 태그가 하나 이상이고 모두 검색 결과에 영향이 없는 유형이면 true. */
export function allWithoutSearchEffect(tagTypes: readonly ReviewTagType[]): boolean {
  return tagTypes.length > 0 && tagTypes.every((tagType) => tagTypeEffect(tagType) === 'none');
}
