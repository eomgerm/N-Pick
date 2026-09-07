import styles from '@/features/wireframes/wireframe.module.css';

export interface SearchResult {
  id: number;
  title: string;
  clip: string;
  time: string;
  duration: string;
  sceneStart: number;
  sceneEnd: number;
  totalDuration: string;
  totalSeconds: number;
  broadcastDate: string;
  filmingDate: string;
  filmingState: 'verified' | 'unknown' | 'unverified';
  shotType: string;
  evidenceType: 'OCR' | '화면 설명' | 'Transcript';
  evidence: string;
  matchedKeywords: string[];
  source: string;
  score: number;
  imageClass: string;
  imageLabel: string;
}

export const results: SearchResult[] = [
  {
    id: 1,
    title: '설 연휴 첫날, 서울역 귀성 인파',
    clip: 'KBC_20260214_뉴스9_교통.mp4',
    time: '00:42 - 00:49',
    duration: '7초',
    sceneStart: 42,
    sceneEnd: 49,
    totalDuration: '02:18',
    totalSeconds: 138,
    broadcastDate: '2026.02.14',
    filmingDate: '2026.02.14',
    filmingState: 'verified',
    shotType: '와이드 숏',
    evidenceType: 'OCR',
    evidence: '서울역 · 설 연휴 귀성객',
    matchedKeywords: ['서울역', '귀성객'],
    source: 'Keyframe OCR · 검증됨',
    score: 96,
    imageClass: styles.imageOne,
    imageLabel: '명절 귀성객으로 붐비는 서울역 대합실',
  },
  {
    id: 2,
    title: '경부고속도로 양방향 정체',
    clip: 'KBC_20250930_추석교통.mp4',
    time: '01:13 - 01:20',
    duration: '7초',
    sceneStart: 73,
    sceneEnd: 80,
    totalDuration: '02:45',
    totalSeconds: 165,
    broadcastDate: '2025.09.30',
    filmingDate: '미상',
    filmingState: 'unknown',
    shotType: '항공 숏',
    evidenceType: '화면 설명',
    evidence: '해 질 무렵 정체된 고속도로',
    matchedKeywords: ['정체', '고속도로'],
    source: 'VLM caption · 미검증',
    score: 89,
    imageClass: styles.imageTwo,
    imageLabel: '해 질 무렵 차량이 정체된 고속도로',
  },
  {
    id: 3,
    title: '한국도로공사 교통상황실',
    clip: 'KBC_20251002_도로공사.mp4',
    time: '00:18 - 00:27',
    duration: '9초',
    sceneStart: 18,
    sceneEnd: 27,
    totalDuration: '02:04',
    totalSeconds: 124,
    broadcastDate: '2025.10.02',
    filmingDate: '2025.10.01',
    filmingState: 'unverified',
    shotType: '미디엄 숏',
    evidenceType: 'Transcript',
    evidence: '귀성길 주요 구간 소통 상황입니다',
    matchedKeywords: ['귀성길', '소통 상황'],
    source: '방송 자막 · 미검증',
    score: 84,
    imageClass: styles.imageThree,
    imageLabel: '도로 CCTV 화면을 확인하는 교통상황실',
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
