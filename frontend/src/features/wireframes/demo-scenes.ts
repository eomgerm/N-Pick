import styles from '@/features/wireframes/wireframe.module.css';

export type VerificationStatus = 'verified' | 'unverified' | 'unknown' | 'rejected' | 'withdrawn';

export type EvidenceVerificationStatus = Extract<VerificationStatus, 'verified' | 'unverified'>;
export type InformationVerificationStatus = Extract<
  VerificationStatus,
  'verified' | 'unverified' | 'unknown'
>;

export interface SearchEvidenceMatch {
  field: '화면 속 글자 (OCR)' | '장면 설명' | '대사';
  value: string;
  source: string;
  status: EvidenceVerificationStatus;
}

const verificationStatusLabels: Record<VerificationStatus, string> = {
  verified: '검증됨',
  unverified: '미검증',
  unknown: '미상',
  rejected: '반려됨',
  withdrawn: '개입 해제',
};

export function getVerificationStatusLabel(status: VerificationStatus) {
  return verificationStatusLabels[status];
}

export interface SearchResult {
  id: number;
  searchResultId?: string | null;
  rank: number;
  displayName: string;
  title: string;
  clip: string;
  time: string;
  duration: string;
  sceneStart: number;
  sceneEnd: number;
  totalDuration: string;
  totalSeconds: number;
  broadcastDate: string | null;
  filmedDate: string | null;
  filmingState: InformationVerificationStatus;
  shotType: string;
  sceneType: string;
  evidenceType: 'OCR' | '화면 설명' | 'Transcript';
  evidence: string;
  matchEvidence: SearchEvidenceMatch;
  matchedKeywords: string[];
  source: string;
  score: number;
  imageClass: string;
  imageLabel: string;
}

export const results: SearchResult[] = [
  {
    id: 1,
    rank: 1,
    displayName: 'KBC 뉴스9 · 설 연휴 교통',
    title: '설 연휴 첫날, 서울역 귀성 인파',
    clip: 'KBC_20260214_뉴스9_교통.mp4',
    time: '00:42 - 00:49',
    duration: '7초',
    sceneStart: 42,
    sceneEnd: 49,
    totalDuration: '02:18',
    totalSeconds: 138,
    broadcastDate: '2026.02.14',
    filmedDate: '2026.02.14',
    filmingState: 'verified',
    shotType: '와이드 숏',
    sceneType: '역사 인파',
    evidenceType: 'OCR',
    evidence: '서울역 · 설 연휴 귀성객',
    matchEvidence: {
      field: '화면 속 글자 (OCR)',
      value: '서울역 · 설 연휴 귀성객',
      source: 'Keyframe OCR',
      status: 'verified',
    },
    matchedKeywords: ['서울역', '귀성객'],
    source: 'Keyframe OCR · 검증됨',
    score: 96,
    imageClass: styles.imageOne,
    imageLabel: '명절 귀성객으로 붐비는 서울역 대합실',
  },
  {
    id: 2,
    rank: 2,
    displayName: 'KBC 추석 교통특보',
    title: '경부고속도로 양방향 정체',
    clip: 'KBC_20250930_추석교통.mp4',
    time: '01:13 - 01:20',
    duration: '7초',
    sceneStart: 73,
    sceneEnd: 80,
    totalDuration: '02:45',
    totalSeconds: 165,
    broadcastDate: '2025.09.30',
    filmedDate: null,
    filmingState: 'unknown',
    shotType: '항공 숏',
    sceneType: '도로 교통',
    evidenceType: '화면 설명',
    evidence: '해 질 무렵 정체된 고속도로',
    matchEvidence: {
      field: '장면 설명',
      value: '해 질 무렵 정체된 고속도로',
      source: 'VLM caption',
      status: 'unverified',
    },
    matchedKeywords: ['정체', '고속도로'],
    source: 'VLM caption · 미검증',
    score: 89,
    imageClass: styles.imageTwo,
    imageLabel: '해 질 무렵 차량이 정체된 고속도로',
  },
  {
    id: 3,
    rank: 3,
    displayName: 'KBC 뉴스특보 · 도로공사',
    title: '한국도로공사 교통상황실',
    clip: 'KBC_20251002_도로공사.mp4',
    time: '00:18 - 00:27',
    duration: '9초',
    sceneStart: 18,
    sceneEnd: 27,
    totalDuration: '02:04',
    totalSeconds: 124,
    broadcastDate: '2025.10.02',
    filmedDate: '2025.10.01',
    filmingState: 'unverified',
    shotType: '미디엄 숏',
    sceneType: '관제실',
    evidenceType: 'Transcript',
    evidence: '귀성길 주요 구간 소통 상황입니다',
    matchEvidence: {
      field: '대사',
      value: '귀성길 주요 구간 소통 상황입니다',
      source: '방송 자막',
      status: 'unverified',
    },
    matchedKeywords: ['귀성길', '소통 상황'],
    source: '방송 자막 · 미검증',
    score: 84,
    imageClass: styles.imageThree,
    imageLabel: '도로 CCTV 화면을 확인하는 교통상황실',
  },
  {
    id: 4,
    rank: 4,
    displayName: 'KBC 뉴스9 · 귀성길 현장',
    title: '톨게이트로 이어지는 귀성 차량 행렬',
    clip: 'KBC_20251003_귀성길현장.mp4',
    time: '00:31 - 00:39',
    duration: '8초',
    sceneStart: 31,
    sceneEnd: 39,
    totalDuration: '02:32',
    totalSeconds: 152,
    broadcastDate: '2025.10.03',
    filmedDate: '2025.10.03',
    filmingState: 'verified',
    shotType: '롱 숏',
    sceneType: '톨게이트 교통',
    evidenceType: 'OCR',
    evidence: '귀성길 정체 시작 · 서울요금소',
    matchEvidence: {
      field: '화면 속 글자 (OCR)',
      value: '귀성길 정체 시작 · 서울요금소',
      source: 'Keyframe OCR',
      status: 'verified',
    },
    matchedKeywords: ['귀성길', '정체'],
    source: 'Keyframe OCR · 검증됨',
    score: 81,
    imageClass: styles.imageOne,
    imageLabel: '톨게이트 앞으로 길게 늘어선 귀성 차량',
  },
  {
    id: 5,
    rank: 5,
    displayName: 'KBC 추석 연휴 뉴스',
    title: '고속도로 휴게소에 몰린 이용객',
    clip: 'KBC_20251003_휴게소.mp4',
    time: '00:54 - 01:02',
    duration: '8초',
    sceneStart: 54,
    sceneEnd: 62,
    totalDuration: '02:11',
    totalSeconds: 131,
    broadcastDate: '2025.10.03',
    filmedDate: '2025.10.02',
    filmingState: 'verified',
    shotType: '와이드 숏',
    sceneType: '휴게소 인파',
    evidenceType: '화면 설명',
    evidence: '휴게소 주차장과 매장을 가득 메운 귀성객',
    matchEvidence: {
      field: '장면 설명',
      value: '휴게소 주차장과 매장을 가득 메운 귀성객',
      source: 'VLM caption',
      status: 'unverified',
    },
    matchedKeywords: ['휴게소', '귀성객'],
    source: 'VLM caption · 미검증',
    score: 78,
    imageClass: styles.imageTwo,
    imageLabel: '귀성객과 차량으로 붐비는 고속도로 휴게소',
  },
  {
    id: 6,
    rank: 6,
    displayName: 'KBC 뉴스와이드 · 연휴 이동',
    title: '고속버스터미널 승차장 대기 행렬',
    clip: 'KBC_20251002_버스터미널.mp4',
    time: '01:06 - 01:15',
    duration: '9초',
    sceneStart: 66,
    sceneEnd: 75,
    totalDuration: '02:36',
    totalSeconds: 156,
    broadcastDate: '2025.10.02',
    filmedDate: '2025.10.02',
    filmingState: 'verified',
    shotType: '미디엄 와이드 숏',
    sceneType: '터미널 인파',
    evidenceType: 'Transcript',
    evidence: '버스를 기다리는 귀성객 행렬이 길게 이어집니다',
    matchEvidence: {
      field: '대사',
      value: '버스를 기다리는 귀성객 행렬이 길게 이어집니다',
      source: '방송 자막',
      status: 'verified',
    },
    matchedKeywords: ['귀성객', '버스터미널'],
    source: '방송 자막 · 검증됨',
    score: 75,
    imageClass: styles.imageThree,
    imageLabel: '고속버스터미널 승차장에서 차례를 기다리는 승객들',
  },
  {
    id: 7,
    rank: 7,
    displayName: 'KBC 교통정보센터',
    title: '정체 구간을 안내하는 도로 전광판',
    clip: 'KBC_20251001_교통전광판.mp4',
    time: '00:22 - 00:29',
    duration: '7초',
    sceneStart: 22,
    sceneEnd: 29,
    totalDuration: '01:58',
    totalSeconds: 118,
    broadcastDate: '2025.10.01',
    filmedDate: '2025.10.01',
    filmingState: 'verified',
    shotType: '클로즈업 숏',
    sceneType: '교통 안내',
    evidenceType: 'OCR',
    evidence: '경부선 정체 · 우회도로 이용',
    matchEvidence: {
      field: '화면 속 글자 (OCR)',
      value: '경부선 정체 · 우회도로 이용',
      source: 'Keyframe OCR',
      status: 'verified',
    },
    matchedKeywords: ['경부선', '정체'],
    source: 'Keyframe OCR · 검증됨',
    score: 72,
    imageClass: styles.imageOne,
    imageLabel: '고속도로 정체와 우회 정보를 표시하는 전광판',
  },
  {
    id: 8,
    rank: 8,
    displayName: 'KBC 교통 자료영상',
    title: '명절 고속도로 정체 자료화면',
    clip: 'KBC_ARCHIVE_명절고속도로.mp4',
    time: '00:47 - 00:56',
    duration: '9초',
    sceneStart: 47,
    sceneEnd: 56,
    totalDuration: '02:20',
    totalSeconds: 140,
    broadcastDate: null,
    filmedDate: '2024.09.16',
    filmingState: 'verified',
    shotType: '항공 숏',
    sceneType: '자료화면 교통',
    evidenceType: '화면 설명',
    evidence: '명절 연휴 차량으로 가득 찬 고속도로',
    matchEvidence: {
      field: '장면 설명',
      value: '명절 연휴 차량으로 가득 찬 고속도로',
      source: 'VLM caption',
      status: 'unverified',
    },
    matchedKeywords: ['명절', '고속도로'],
    source: 'VLM caption · 미검증',
    score: 69,
    imageClass: styles.imageTwo,
    imageLabel: '명절 차량이 꼬리를 물고 이동하는 고속도로 자료화면',
  },
  {
    id: 9,
    rank: 9,
    displayName: 'KBC 뉴스9 · 도로 통제',
    title: '사고 수습으로 통제된 고속도로 차로',
    clip: 'KBC_20250929_고속도로사고.mp4',
    time: '01:24 - 01:32',
    duration: '8초',
    sceneStart: 84,
    sceneEnd: 92,
    totalDuration: '02:48',
    totalSeconds: 168,
    broadcastDate: '2025.09.29',
    filmedDate: '2025.09.29',
    filmingState: 'unverified',
    shotType: '롱 숏',
    sceneType: '도로 사고',
    evidenceType: 'Transcript',
    evidence: '사고 수습 여파로 두 개 차로가 통제되고 있습니다',
    matchEvidence: {
      field: '대사',
      value: '사고 수습 여파로 두 개 차로가 통제되고 있습니다',
      source: '방송 자막',
      status: 'unverified',
    },
    matchedKeywords: ['차로 통제', '정체'],
    source: '방송 자막 · 미검증',
    score: 66,
    imageClass: styles.imageThree,
    imageLabel: '사고 수습 차량 주변으로 통제된 고속도로 차로',
  },
  {
    id: 10,
    rank: 10,
    displayName: 'KBC 아침뉴스 · 교통 점검',
    title: '새벽 경부고속도로의 원활한 차량 흐름',
    clip: 'KBC_20250928_새벽교통.mp4',
    time: '00:12 - 00:20',
    duration: '8초',
    sceneStart: 12,
    sceneEnd: 20,
    totalDuration: '01:46',
    totalSeconds: 106,
    broadcastDate: '2025.09.28',
    filmedDate: null,
    filmingState: 'unknown',
    shotType: '와이드 숏',
    sceneType: '도로 교통',
    evidenceType: '화면 설명',
    evidence: '새벽 시간 원활하게 이동하는 고속도로 차량',
    matchEvidence: {
      field: '장면 설명',
      value: '새벽 시간 원활하게 이동하는 고속도로 차량',
      source: 'VLM caption',
      status: 'unverified',
    },
    matchedKeywords: ['경부고속도로', '차량 흐름'],
    source: 'VLM caption · 미검증',
    score: 63,
    imageClass: styles.imageOne,
    imageLabel: '새벽 시간 한산한 경부고속도로를 달리는 차량',
  },
];

export function formatTimestamp(totalSeconds: number) {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;

  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export function getKeyframeTimes(result: SearchResult) {
  const lastFrameSecond = Math.max(result.sceneStart, result.sceneEnd - 1);
  const sceneDuration = lastFrameSecond - result.sceneStart;

  return [0, 0.5, 1].map((position) =>
    formatTimestamp(Math.round(result.sceneStart + sceneDuration * position)),
  );
}
