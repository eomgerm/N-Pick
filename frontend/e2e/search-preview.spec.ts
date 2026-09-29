import { expect, test } from '@playwright/test';
import { searchFixture } from './search-fixture';

test.beforeEach(async ({ context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
});

test('검색 요약은 서버 해석 상태를 표시하고 모바일·실패에도 검색어와 구분한다', async ({
  page,
}, testInfo) => {
  let responseState: 'resolved' | 'fallback' | 'failed' = 'resolved';
  await page.route('**/api/v1/search', (route) =>
    route.fulfill(
      responseState === 'failed'
        ? {
            status: 503,
            json: { isSuccess: false, code: 'SEARCH_503', message: '검색을 사용할 수 없습니다.' },
          }
        : {
            json: {
              isSuccess: true,
              code: 'COMM_200',
              message: '성공',
              data: {
                ...searchFixture,
                status: responseState === 'fallback' ? 'degraded' : 'succeeded',
                degraded_reasons: responseState === 'fallback' ? ['resolver_fallback'] : [],
                query_resolution_status: responseState,
              },
            },
          },
    ),
  );
  await page.goto('/search/results?q=서울%20귀성%20교통');
  // 상단 검색 요약은 검색창·결과 제목과 중복이라 없앴다(S15P21A501-294). 해석 상태는 결과
  // 안내(정상은 알림 영역, 해석 누락은 기능 누락 고지, 실패는 실패 패널의 '검색 해석')로 전달된다.
  const input = page.getByRole('textbox', { name: '뉴스 장면 검색어' });
  const announcement = page.getByText('서울 귀성 교통 검색 결과 1개. 정상 검색', { exact: true });
  const degraded = page.getByRole('region', { name: '검색 기능 누락 안내', exact: true });
  await expect(input).toHaveValue('서울 귀성 교통');
  await expect(announcement).toBeAttached();
  await expect(degraded).toHaveCount(0);

  responseState = 'fallback';
  await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(degraded).toContainText('검색어 해석 일부 누락');
  await expect(degraded).toContainText(
    '검색어 해석을 사용할 수 없어 기본 단어 검색으로 결과를 제공했어요.',
  );
  await expect(announcement).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(degraded).toBeVisible();
  const bounds = (await degraded.boundingBox())!;
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath('search-interpretation-mobile.png') });

  responseState = 'failed';
  await page.getByRole('button', { name: '검색', exact: true }).click();
  const failure = page.getByRole('alert').filter({ hasText: '검색 결과를 불러오지 못했어요' });
  const resolverTerm = failure.getByRole('term').filter({ hasText: '검색 해석' });
  await expect(resolverTerm).toBeVisible();
  await expect(failure.getByRole('definition').last()).toHaveText('확인하지 못함');
  await expect(failure.getByRole('definition').first()).toHaveText('서울 귀성 교통');
  await expect(degraded).toHaveCount(0);
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
  await expect(dialog.getByText('자동 인식', { exact: true })).toBeVisible();
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

test('Preview 문의는 저장 결과 ID로 접수하고 재시도 키와 재검색 결과를 구분한다', async ({
  page,
}, testInfo) => {
  await page.clock.install();
  const successToast = page.getByRole('status').filter({ hasText: '문의가 접수되었습니다' });
  const requests: { url: string; key: string | undefined; body: unknown }[] = [];
  await page.route('**/api/v1/search/results/*/inquiries', async (route) => {
    const request = route.request();
    requests.push({
      url: request.url(),
      key: request.headers()['idempotency-key'],
      body: request.postDataJSON(),
    });
    await route.fulfill(
      requests.length === 1
        ? {
            status: 503,
            json: { isSuccess: false, code: 'COMM_503', message: '잠시 후 다시 시도해 주세요.' },
          }
        : {
            json: {
              isSuccess: true,
              code: 'COMM_200',
              message: '성공',
              data: { feedbackId: '501', status: 'OPEN' },
            },
          },
    );
  });
  await page.goto('/search/results?q=장면');
  const previewButton = page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' });
  await previewButton.click();
  await page.getByRole('button', { name: '이상해요', exact: true }).click();
  const inquiry = page.getByRole('dialog', { name: '이 장면에 이상이 있나요?' });
  await expect(successToast).toHaveCount(0);
  await inquiry.getByRole('radio', { name: '기타', exact: true }).check();
  await inquiry.getByRole('textbox').fill('  장면을 확인해 주세요  ');
  await inquiry.getByRole('button', { name: '문의 접수', exact: true }).click();
  await expect(inquiry.getByRole('button', { name: '다시 시도', exact: true })).toBeVisible();
  await expect(successToast).toHaveCount(0);
  await inquiry.getByRole('button', { name: '다시 시도', exact: true }).click();
  await expect(inquiry).not.toBeVisible();
  await expect(page.getByText(/문의가 접수되었습니다/)).toBeVisible();
  // 서버 문의 ID(TSID)는 사용자에게 의미가 없으므로 토스트에 노출하지 않는다 (S15P21A501-325).
  await expect(successToast).not.toContainText('#501');
  await expect(successToast).toBeVisible();
  await expect(successToast).toHaveCSS('position', 'fixed');
  await expect(successToast).toBeInViewport();
  await page.screenshot({ path: testInfo.outputPath('inquiry-success-toast.png') });
  await page.clock.fastForward(5_000);
  await expect(successToast).toHaveCount(0);
  expect(requests).toHaveLength(2);
  expect(requests[0].url).toMatch(/\/search\/results\/101\/inquiries$/);
  expect(requests[0].body).toEqual({ comment: '장면을 확인해 주세요' });
  expect(requests[0].key).toBeTruthy();
  expect(requests[1]).toEqual(requests[0]);

  await previewButton.click();
  await expect(page.getByRole('button', { name: '접수됨', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Preview 닫기' }).click();
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: {
          ...searchFixture,
          search_execution_id: '200',
          results: searchFixture.results.map((result) => ({ ...result, search_result_id: '201' })),
        },
      },
    }),
  );
  const searchResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/v1/search'),
  );
  await page.getByRole('button', { name: '검색', exact: true }).click();
  await searchResponse;
  await previewButton.click();
  await page.getByRole('button', { name: '이상해요', exact: true }).click();
  await inquiry.getByRole('button', { name: '문의 접수', exact: true }).click();
  await expect(inquiry).not.toBeVisible();
  await expect(successToast).toBeVisible();
  await page.clock.fastForward(5_000);
  await expect(successToast).toHaveCount(0);
  expect(requests).toHaveLength(3);
  expect(requests[2].url).toMatch(/\/search\/results\/201\/inquiries$/);
  expect(requests[2].key).not.toBe(requests[0].key);
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
    page.getByRole('button', { name: /방송일 2026\.09\.01 – 2026\.09\.11/ }).first(),
  ).toBeVisible();
  await expect(page.getByText('0건', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Preview 열기/ })).toHaveCount(0);
});
