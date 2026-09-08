export type InquiryStatus = 'open' | 'reviewing' | 'closed';

export type InquiryResolution =
  'exclude_scene' | 'no_action' | 'deferred' | 'tag_correction' | 'patch_parse';

export const inquiryStatusLabels: Record<InquiryStatus, string> = {
  open: '접수',
  reviewing: '검수 중',
  closed: '종료',
};

export const inquiryResolutionLabels: Record<InquiryResolution, string> = {
  exclude_scene: '특정 검색에서 장면 제외',
  no_action: '조치 없이 종료',
  deferred: '처리 보류',
  tag_correction: '태그 교정',
  patch_parse: '검색 해석 교정',
};
