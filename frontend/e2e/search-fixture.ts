export const searchFixture = {
  search_execution_id: '100',
  status: 'succeeded',
  degraded_reasons: [],
  query_resolution_status: 'resolved',
  has_applied_review_rule: false,
  guard_summary: { excluded_result_count: 0, reasons: [] },
  shortage_reasons: ['candidate_pool_exhausted'],
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
