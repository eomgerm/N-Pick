export const searchEvidenceFieldLabels = {
  caption: '장면 설명',
  ocr: '화면 속 글자 (OCR)',
  transcript: '대사',
  tag: '태그',
} as const;

// `source`는 검색 응답 계약에서 비어 있지 않은 문자열이지만, 화면에는 알려진 값만 한국어로 옮긴다.
export const searchEvidenceSourceLabels: Readonly<Record<string, string>> = {
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

export const searchShotTypeLabels = {
  anchor: '앵커',
  interview: '인터뷰',
  b_roll: '자료 화면',
  unknown: '정보 없음',
} as const;

export function getSearchEvidenceFieldLabel(value: unknown): string {
  return typeof value === 'string'
    ? (searchEvidenceFieldLabels[value as keyof typeof searchEvidenceFieldLabels] ?? '정보 없음')
    : '정보 없음';
}

export function getSearchEvidenceSourceLabel(value: unknown): string {
  return typeof value === 'string'
    ? (searchEvidenceSourceLabels[value as keyof typeof searchEvidenceSourceLabels] ?? '정보 없음')
    : '정보 없음';
}

export function getSearchShotTypeLabel(value: unknown): string {
  return typeof value === 'string'
    ? (searchShotTypeLabels[value as keyof typeof searchShotTypeLabels] ?? '정보 없음')
    : '정보 없음';
}
