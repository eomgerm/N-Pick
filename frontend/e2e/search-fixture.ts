export const searchFixture = {
  search_execution_id: '100',
  status: 'succeeded',
  degraded_reasons: [],
  query_resolution_status: 'resolved',
  has_applied_review_rule: false,
  guard_summary: { excluded_result_count: 0, reasons: [] },
  shortage_reasons: ['candidate_pool_exhausted'],
  // 단일 페이지 픽스처: 더보기 없음. has_next 는 실시간 검색 응답의 필수 boolean 이다
  // (web-api §5, search-api-contract). 빠지면 파서가 응답을 거절해 결과가 렌더되지 않는다.
  has_next: false,
  results: [
    {
      search_result_id: '101',
      scene_id: '31',
      clip_id: '21',
      rank: 1,
      display_name: '검색 테스트 원본',
      scene_description: '실제 응답 장면',
      start_time_ms: 1250,
      end_time_ms: 2500,
      broadcast_date: { value: null, verification_status: 'unknown' },
      filmed_date: { value: null, verification_status: 'unknown' },
      shot_type: 'b_roll',
      scene_type: null,
      matched_keywords: ['장면'],
      match_evidence: [
        {
          field: 'caption',
          value: '테스트 장면 설명',
          source: 'vlm',
          verification_status: 'unverified',
        },
      ],
    },
  ],
};
