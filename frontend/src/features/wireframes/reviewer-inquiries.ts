import type { InquiryResolution, InquiryStatus } from '@/features/wireframes/inquiry-state';

interface InquirySnapshot {
  id: string;
  query: string;
  receivedAt: string;
  sceneId: string;
  sceneTitle: string;
  sceneVersion: number;
  timecode: string;
  rank: number;
  comment: string;
  isDegraded: boolean;
  explicitFilters: { label: string; value: string }[];
  evidence: string;
  guard: string;
  initialResolution: string;
  contextIds: string[];
  beforeTop10: string[];
  afterTop10: string[];
  afterTop10SceneIds: string[];
}

export interface Inquiry extends InquirySnapshot {
  requester: string;
  topic: string;
  daysAgo: number;
  thumbnail: 'station' | 'weather' | 'square';
  initialStatus: InquiryStatus;
  initialOutcome?: InquiryResolution;
}
const baseInquiries: InquirySnapshot[] = [
  {
    id: 'INQ-1042',
    query: '2025년 추석 경부고속도로 귀성길 정체',
    receivedAt: '오늘 14:32',
    sceneId: 'scene_2d9af',
    sceneTitle: '서울역 귀성객 인터뷰',
    sceneVersion: 3,
    timecode: '00:42–00:49',
    rank: 2,
    comment: '고속도로 장면을 찾았는데 역 내부 인터뷰가 함께 나옵니다.',
    isDegraded: false,
    explicitFilters: [
      { label: '방송일', value: '2025.09.01–2025.10.10' },
      { label: '촬영일', value: '미지정' },
      { label: '장면 유형', value: '현장 자료화면 포함' },
    ],
    evidence: '영상 속 글자: “서울역 귀성객” (확인된 정보)',
    guard:
      '방송일은 검색 조건과 일치해요. 장소는 다르지만, 관련성이 있을 수 있어 검색 결과에 포함됐어요.',
    initialResolution: `{
  "schema_version": "resolution-v1",
  "intent": "scene_search",
  "date_windows": [{"field":"broadcast_date","start":"2025-09-01","end_exclusive":"2025-10-11","origin":"explicit_filter","query_span":null,"confidence":1}],
  "incident_names": [{"value":"추석","origin":"explicit_query","query_span":{"start":6,"end":8},"confidence":1}],
  "entities": [{"type":"organization","value":"한국도로공사","origin":"inferred","query_span":null,"confidence":0.72}],
  "locations": [{"type":"location","value":"경부고속도로","origin":"explicit_query","query_span":{"start":9,"end":15},"confidence":1}],
  "expanded_terms": ["귀성 차량", "고속도로 정체"],
  "confidence": 0.92
}`,
    contextIds: ['scene_2d9af', 'evidence_a81c'],
    beforeTop10: [
      '경부고속도로 정체',
      '서울역 귀성객 인터뷰',
      '교통상황실 브리핑',
      '톨게이트 진입 차량',
      '서해안고속도로 정체',
      '고속버스 터미널',
      '귀성 차량 행렬',
      '휴게소 혼잡',
      '도로공사 CCTV',
      '추석 연휴 교통 예보',
    ],
    afterTop10: [
      '경부고속도로 정체',
      '교통상황실 브리핑',
      '톨게이트 진입 차량',
      '서해안고속도로 정체',
      '고속버스 터미널',
      '귀성 차량 행렬',
      '휴게소 혼잡',
      '도로공사 CCTV',
      '추석 연휴 교통 예보',
      '귀경길 버스전용차로',
    ],
    afterTop10SceneIds: [
      'scene_101',
      'scene_102',
      'scene_103',
      'scene_104',
      'scene_105',
      'scene_106',
      'scene_107',
      'scene_108',
      'scene_109',
      'scene_110',
    ],
  },
  {
    id: 'INQ-1039',
    query: '2022년 태풍 힌남노 부산 해안',
    receivedAt: '오늘 13:18',
    sceneId: 'scene_77cb1',
    sceneTitle: '2023년 태풍 카눈 자료화면',
    sceneVersion: 2,
    timecode: '01:08–01:15',
    rank: 1,
    comment: '연도와 태풍 이름이 모두 다른 장면입니다.',
    isDegraded: true,
    explicitFilters: [
      { label: '방송일', value: '2022.01.01–2022.12.31' },
      { label: '촬영일', value: '미지정' },
      { label: '지역', value: '부산' },
    ],
    evidence: '방송일: 2023년 8월 10일 (확인된 정보)',
    guard: '당시 비슷한 의미를 찾는 검색이 지연되어, 단어와 날짜 등 기본 조건으로 검색한 결과예요.',
    initialResolution: `{
  "schema_version": "resolution-v1",
  "intent": "scene_search",
  "date_windows": [{"field":"broadcast_date","start":"2022-01-01","end_exclusive":"2023-01-01","origin":"explicit_query","query_span":{"start":0,"end":5},"confidence":1}],
  "incident_names": [{"value":"태풍 힌남노","origin":"explicit_query","query_span":{"start":6,"end":12},"confidence":1}],
  "entities": [],
  "locations": [{"type":"location","value":"부산 해안","origin":"explicit_query","query_span":{"start":13,"end":18},"confidence":1}],
  "expanded_terms": ["월파", "강풍", "해안 피해"],
  "confidence": 0.86
}`,
    contextIds: ['scene_77cb1', 'evidence_c102'],
    beforeTop10: [
      '2023년 태풍 카눈 자료화면',
      '부산 해안 월파',
      '태풍 대비 현장',
      '힌남노 복구 현장',
      '광안리 통제',
      '해운대 높은 파도',
      '부산항 피항 선박',
      '태풍 상황실',
      '침수 도로',
      '강풍 피해',
    ],
    afterTop10: [
      '부산 해안 월파',
      '태풍 대비 현장',
      '힌남노 복구 현장',
      '광안리 통제',
      '해운대 높은 파도',
      '부산항 피항 선박',
      '태풍 상황실',
      '침수 도로',
      '강풍 피해',
      '힌남노 북상 경로',
    ],
    afterTop10SceneIds: [
      'scene_201',
      'scene_202',
      'scene_203',
      'scene_204',
      'scene_205',
      'scene_206',
      'scene_207',
      'scene_208',
      'scene_209',
      'scene_210',
    ],
  },
  {
    id: 'INQ-1036',
    query: '서울 시청 앞 대규모 집회',
    receivedAt: '어제 17:04',
    sceneId: 'scene_4aa80',
    sceneTitle: '광화문 광장 전경',
    sceneVersion: 5,
    timecode: '00:12–00:19',
    rank: 4,
    comment: '화면 설명의 장소가 실제 촬영 위치와 다른 것 같습니다.',
    isDegraded: false,
    explicitFilters: [
      { label: '방송일', value: '전체 기간' },
      { label: '촬영일', value: '미지정' },
      { label: '지역', value: '서울' },
    ],
    evidence: '자동 작성된 장면 설명: “서울 도심 광장” (확인 필요)',
    guard: '장소가 정확한지 아직 확인되지 않아 검색 결과에 포함됐어요.',
    initialResolution: `{
  "schema_version": "resolution-v1",
  "intent": "scene_search",
  "date_windows": [],
  "incident_names": [],
  "entities": [],
  "locations": [{"type":"facility","value":"서울 시청","origin":"explicit_query","query_span":{"start":0,"end":5},"confidence":1}],
  "expanded_terms": ["집회", "도심 군중", "시청 광장"],
  "confidence": 0.81
}`,
    contextIds: ['scene_4aa80', 'evidence_90fd'],
    beforeTop10: [
      '시청 앞 집회',
      '도심 교통 통제',
      '집회 참가자 행진',
      '광화문 광장 전경',
      '경찰 차벽',
      '시민 인터뷰',
      '서울광장 전경',
      '도심 버스 우회',
      '집회 무대',
      '시청역 출구',
    ],
    afterTop10: [
      '시청 앞 집회',
      '도심 교통 통제',
      '집회 참가자 행진',
      '경찰 차벽',
      '시민 인터뷰',
      '서울광장 전경',
      '도심 버스 우회',
      '집회 무대',
      '시청역 출구',
      '시청 앞 교통 CCTV',
    ],
    afterTop10SceneIds: [
      'scene_301',
      'scene_302',
      'scene_303',
      'scene_304',
      'scene_305',
      'scene_306',
      'scene_307',
      'scene_308',
      'scene_309',
      'scene_310',
    ],
  },
];

// 기존 세 검수 시나리오를 서로 독립된 문의로 구성한 페이지네이션 데모입니다.
const requesters = ['김서연', '이준호', '박지민', '최유진', '정민수'];
const topics = ['교통', '날씨', '사회'];
const thumbnails = ['station', 'weather', 'square'] as const;

export const inquiries: Inquiry[] = Array.from({ length: 23 }, (_, index) => {
  const source = baseInquiries[index % baseInquiries.length];
  return {
    ...source,
    id: index < 3 ? source.id : `INQ-${1036 - index}`,
    receivedAt: index === 0 ? '오늘 14:32' : `${index}일 전`,
    daysAgo: index,
    requester: requesters[index % requesters.length],
    topic: topics[index % topics.length],
    thumbnail: thumbnails[index % thumbnails.length],
    initialStatus:
      index < 3 || index % 5 === 0 || index % 5 === 3
        ? 'open'
        : index % 5 === 1
          ? 'reviewing'
          : 'closed',
    initialOutcome:
      index < 3 || index % 5 === 0 || index % 5 === 3 || index % 5 === 1 ? undefined : 'no_action',
  };
});
