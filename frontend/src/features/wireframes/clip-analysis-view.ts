import type {
  ClipAnalysisTagScope,
  ClipAnalysisTagType,
  ClipAnalysisTagVerification,
  ClipAnalysisTranscriptSource,
} from '@/features/wireframes/clip-analysis-api';
import { formatMediaTime } from '@/features/wireframes/media-time';

const tagTypeLabels: Record<ClipAnalysisTagType, string> = {
  person: '인물',
  organization: '기관',
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

export function formatSceneInterval(startTimeMs: number, endTimeMs: number) {
  return `${formatMediaTime(startTimeMs / 1000)}–${formatMediaTime(endTimeMs / 1000)}`;
}

export function transcriptSourceLabel(source: ClipAnalysisTranscriptSource | null) {
  return source === 'provided' ? '제공 자막' : source === 'asr' ? '음성 인식' : '출처 미확인';
}

export function tagTypeLabel(type: ClipAnalysisTagType) {
  return tagTypeLabels[type];
}

export function tagScopeLabel(scope: ClipAnalysisTagScope) {
  return scope === 'scene' ? '이 장면' : '영상 전체';
}

export function tagVerificationLabel(verification: ClipAnalysisTagVerification) {
  return verification === 'reviewer_verified'
    ? '검수 확인'
    : verification === 'verified'
      ? '근거 확인'
      : '자동 분석';
}
