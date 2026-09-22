import { expect, test } from '@playwright/test';

// 더보기(offset 페이지네이션, S15P21A501-251)의 실제 누적 동작 e2e. 리뷰에서 「실제 더보기가
// 동작하는 것을 아무도 확인하지 않았다」고 지적된 지점을 닫는다.

const scene = (i: number, rank: number) => ({
  search_result_id: String(1000 + i),
  scene_id: String(2000 + i),
  clip_id: '21',
  rank,
  display_name: `클립 ${i}`,
  scene_description: `장면 ${i}`,
  start_time_ms: 1000,
  end_time_ms: 2000,
  broadcast_date: { value: null, verification_status: 'unknown' },
  filmed_date: { value: null, verification_status: 'unknown' },
  shot_type: 'b_roll',
  scene_type: null,
  matched_keywords: ['장면'],
  match_evidence: [
    { field: 'caption', value: '설명', source: 'vlm', verification_status: 'unverified' },
  ],
});

const base = {
  search_execution_id: '100',
  status: 'succeeded',
  degraded_reasons: [],
  query_resolution_status: 'resolved',
  has_applied_review_rule: false,
  guard_summary: { excluded_result_count: 0, reasons: [] },
};
// page 0: 10건 채움 → shortage 아님(shortage_reasons 비움), 다음 페이지 있음
const page0 = {
  ...base,
  shortage_reasons: [],
  has_next: true,
  results: Array.from({ length: 10 }, (_, k) => scene(k + 1, k + 1)),
};
// page 1: 3건 → shortage, 다음 페이지 없음
const page1 = {
  ...base,
  shortage_reasons: ['candidate_pool_exhausted'],
  has_next: false,
  results: Array.from({ length: 3 }, (_, k) => scene(k + 11, k + 1)),
};
const ok = (data: unknown) => ({ isSuccess: true, code: 'COMM_200', message: '성공', data });

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
});

test('더보기로 다음 페이지를 누적하고 마지막 페이지에서 버튼이 사라진다', async ({ page }) => {
  const pageParams: (number | undefined)[] = [];
  await page.route('**/api/v1/search', (route) => {
    const body = route.request().postDataJSON() as { page?: number };
    pageParams.push(body.page);
    return route.fulfill({ json: ok(body.page === 1 ? page1 : page0) });
  });

  await page.goto('/search/results?q=장면');
  const cards = page.getByRole('button', { name: /Preview 열기$/ });
  await expect(cards).toHaveCount(10);

  const loadMore = page.getByRole('button', { name: '더보기', exact: true });
  await expect(loadMore).toBeVisible();
  await loadMore.click();

  // page 1이 누적되어 총 13건, 마지막 페이지라 더보기 버튼은 사라진다
  await expect(cards).toHaveCount(13);
  await expect(loadMore).toHaveCount(0);

  // 첫 요청은 page 를 싣지 않고(계약), 더보기 요청만 page:1 을 싣는다
  expect(pageParams).toEqual([undefined, 1]);
});
