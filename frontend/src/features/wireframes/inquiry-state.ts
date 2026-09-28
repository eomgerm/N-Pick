export type InquiryStatus = 'open' | 'reviewing' | 'closed';

// exclude_scene·deferred·tag_correction·patch_parse 는 교정 토글 도입 이전에 저장된 문의를 읽기
// 위해 남겨 둔 값이다(백엔드 FeedbackResolution 참고). 신규 저장은 no_action·correction 두 값만 쓴다.
export type InquiryResolution =
  'exclude_scene' | 'no_action' | 'deferred' | 'tag_correction' | 'patch_parse' | 'correction';

export const inquiryStatusLabels: Record<InquiryStatus, string> = {
  open: '접수',
  reviewing: '검수 중',
  closed: '종료',
};

export const inquiryResolutionLabels: Record<InquiryResolution, string> = {
  exclude_scene: '특정 검색에서 장면 제외',
  no_action: '오류없음',
  deferred: '처리 보류',
  tag_correction: '태그 교정',
  patch_parse: '검색 해석 교정',
  correction: '교정',
};
