import { expect, test } from '@playwright/test';
import { searchFixture } from './search-fixture';

test.beforeEach(async ({ context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
});

test('검색 POST의 실제 응답 카드에서 clip ID와 밀리초 구간으로 원본을 재생한다', async ({
  page,
}) => {
  const request = page.waitForRequest(
    (request) => request.url().endsWith('/api/v1/search') && request.method() === 'POST',
  );
  await page.goto('/search/results?q=장면');
  expect((await request).postDataJSON()).toEqual({ query: '장면', explicit_filters: {} });
  const media = page.waitForResponse(
    (response) => response.url().endsWith('/media/21') && response.status() === 206,
  );
  await page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' }).click();
  await media;
  const dialog = page.getByRole('dialog');
  await expect
    .poll(() => dialog.locator('video').evaluate((video: HTMLVideoElement) => video.currentTime))
    .toBeGreaterThanOrEqual(1.25);
  await expect(dialog.getByText('테스트 장면 설명')).toBeVisible();
  await expect(dialog.getByText('미검증', { exact: true })).toBeVisible();
});

test('검색 실패를 데모 카드로 대체하지 않고 수동 재시도한다', async ({ page }) => {
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      status: 503,
      json: { isSuccess: false, code: 'SEARCH_503', message: '검색을 사용할 수 없습니다.' },
    }),
  );
  await page.goto('/search/results?q=장면');
  await expect(
    page.getByRole('button', { name: '같은 조건으로 다시 시도', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: /Preview 열기/ })).toHaveCount(0);
  await page.unroute('**/api/v1/search');
  await page.getByRole('button', { name: '같은 조건으로 다시 시도', exact: true }).click();
  await expect(page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' })).toBeVisible();
});

test('degraded 검색은 Preview에서도 같은 경고를 유지한다', async ({ page }) => {
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: { ...searchFixture, status: 'degraded', degraded_reasons: ['dense_unavailable'] },
      },
    }),
  );
  await page.goto('/search/results?q=장면');
  await page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' }).click();
  await expect(
    page.getByRole('dialog').getByText('의미 검색 일부 누락', { exact: true }),
  ).toBeVisible();
});

test('실제 검색 응답이 빈 결과여도 resolver fallback 안내와 선택 기간을 유지한다', async ({
  page,
}) => {
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: {
          ...searchFixture,
          status: 'degraded',
          degraded_reasons: ['resolver_fallback'],
          query_resolution_status: 'fallback',
          results: [],
        },
      },
    }),
  );
  await page.goto('/search/results?q=장면&broadcastFrom=2026-09-01&broadcastTo=2026-09-11');
  await expect(page.getByRole('heading', { name: '조건에 맞는 장면이 없어요' })).toBeVisible();
  await expect(page.getByText('검색어 해석 일부 누락', { exact: true })).toBeVisible();
  await expect(
    page.getByText('해석을 사용할 수 없어 기본 단어 검색으로 전환', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '방송일 기간 선택: 2026.09.01 – 2026.09.11' }).first(),
  ).toBeVisible();
  await expect(page.getByText('0건', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Preview 열기/ })).toHaveCount(0);
});
