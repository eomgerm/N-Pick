import { expect, test, type Page, type Route } from '@playwright/test';
import { searchFixture } from './search-fixture';

async function openAsEditor(page: Page, path: string) {
  await page.clock.setFixedTime(new Date('2026-09-11T03:00:00Z'));
  await page.context().addCookies([
    {
      name: 'JSESSIONID',
      value: 'e2e-editor',
      url: 'http://127.0.0.1:3116',
    },
  ]);
  await page.goto(path);
}

async function applyDateRange(page: Page, label: string, from: string, to: string) {
  await page.getByRole('button', { name: new RegExp(`^${label} 기간 선택:`) }).click();
  const dialog = page
    .getByRole('dialog')
    .filter({ has: page.getByRole('heading', { name: `${label} 기간` }) });
  if (from.startsWith('2026-08')) {
    await dialog.getByRole('button', { name: '시작일 이전 달' }).click();
    await dialog.getByRole('button', { name: '종료일 이전 달' }).click();
  }
  await dialog.locator(`[data-endpoint="from"] [data-date="${from}"]`).click();
  await dialog.locator(`[data-endpoint="to"] [data-date="${to}"]`).click();
  await dialog.getByRole('button', { name: '적용' }).click();
  await expect(dialog).not.toBeVisible();
}

test('검색 오류 팝업은 자동·수동으로 닫히고 재시도 오류를 다시 알린다', async ({ page }) => {
  await page.clock.install();
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      status: 404,
      json: {
        isSuccess: false,
        code: 'COMM_404',
        message: '요청한 항목을 찾을 수 없습니다.',
        requestId: 'search-error-test',
      },
    }),
  );
  await openAsEditor(page, '/search/results?q=장면');

  const notice = page.getByRole('alert').filter({ hasText: '요청한 항목을 찾을 수 없습니다.' });
  const retry = page.getByRole('button', { name: '같은 조건으로 다시 시도' });
  await expect(notice).toBeVisible();
  await expect(notice).not.toContainText('search-error-test');
  await expect(notice).not.toContainText('COMM_404');
  await expect(notice.locator('..')).toHaveCSS('position', 'fixed');

  await page.clock.fastForward(5_000);
  await expect(notice).toHaveCount(0);
  await expect(retry).toBeVisible();

  await retry.click();
  await expect(notice).toBeVisible();
  await page.getByRole('button', { name: '오류 알림 닫기' }).click();
  await expect(notice).toHaveCount(0);

  await retry.click();
  await expect(notice).toBeVisible();
  await page.clock.fastForward(4_000);
  await expect(notice).toBeVisible();
  await page.clock.fastForward(1_000);
  await expect(notice).toHaveCount(0);
  await expect(page.getByRole('textbox', { name: '뉴스 장면 검색어' })).toHaveValue('장면');
});

test('저장에 실패한 검색 결과 Preview는 문의 요청을 보내지 않는다', async ({ page }) => {
  const inquiryRequests: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/inquiries')) {
      inquiryRequests.push(request.url());
    }
  });
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: {
          ...searchFixture,
          search_execution_id: null,
          status: 'degraded',
          degraded_reasons: ['snapshot_save_failed'],
          results: searchFixture.results.map((result) => ({ ...result, search_result_id: null })),
        },
      },
    }),
  );
  await openAsEditor(page, '/search/results?q=장면');
  await page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' }).click();
  const preview = page.getByRole('dialog');
  const unavailableButton = preview.getByRole('button', { name: '문의 불가' });
  const reason = preview.getByRole('tooltip');
  await expect(unavailableButton).toBeDisabled();
  await expect(reason).toBeHidden();
  await unavailableButton.hover();
  await expect(reason).toHaveText('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.');
  await expect(reason).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('preview-tooltip-desktop.png') });
  await page.mouse.move(0, 0);
  await expect(reason).toBeHidden();
  await unavailableButton.focus();
  await expect(reason).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(preview).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(reason).toBeHidden();
  await page.setViewportSize({ width: 390, height: 844 });
  await unavailableButton.hover();
  await expect(reason).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('preview-tooltip-mobile.png') });
  await page.mouse.move(0, 0);
  await preview.getByRole('complementary', { name: '송출 전 확인 안내' }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: test.info().outputPath('preview-evidence-mobile.png') });
  expect(inquiryRequests).toEqual([]);
});

test('검색어와 방송일·촬영일을 결과 URL과 화면에 보존한다', async ({ page }) => {
  await openAsEditor(page, '/search');

  const search = page.getByRole('search', { name: '뉴스 장면 검색' });
  await search.getByRole('searchbox', { name: '뉴스 장면 검색어' }).fill('  명절 교통  ');
  await applyDateRange(page, '방송일', '2026-09-01', '2026-09-03');
  await applyDateRange(page, '촬영일', '2026-08-28', '2026-08-29');
  await search.getByRole('button', { name: '장면 찾기' }).click();

  await expect(page).toHaveURL((url) => {
    const params = url.searchParams;
    return (
      url.pathname === '/search/results' &&
      params.get('q') === '명절 교통' &&
      params.get('broadcastFrom') === '2026-09-01' &&
      params.get('broadcastTo') === '2026-09-03' &&
      params.get('filmingFrom') === '2026-08-28' &&
      params.get('filmingTo') === '2026-08-29'
    );
  });
  await expect(
    page.getByRole('button', { name: '방송일 기간 선택: 2026.09.01 – 2026.09.03' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '촬영일 기간 선택: 2026.08.28 – 2026.08.29' }),
  ).toBeVisible();
});

test('방송일·촬영일 프리셋을 각각 적용하고 초기화는 즉시 반영한다', async ({ page }) => {
  await openAsEditor(page, '/search');

  const broadcastTrigger = page.getByRole('button', { name: /^방송일 기간 선택:/ });
  await broadcastTrigger.click();
  const broadcastDialog = page
    .getByRole('dialog')
    .filter({ has: page.getByRole('heading', { name: '방송일 기간' }) });
  await broadcastDialog.getByRole('button', { name: '최근 1년' }).click();
  await expect(
    broadcastDialog.getByRole('button', { name: '시작일 2025년 9월, 월 선택', exact: true }),
  ).toBeVisible();
  await expect(
    broadcastDialog.locator('[data-endpoint="from"] [data-date="2025-09-11"]'),
  ).toHaveAttribute('tabindex', '0');
  await broadcastDialog.getByRole('button', { name: '적용' }).click();
  await expect(
    page.getByRole('button', { name: '방송일 기간 선택: 2025.09.11 – 2026.09.11' }),
  ).toBeVisible();

  const filmingTrigger = page.getByRole('button', { name: /^촬영일 기간 선택:/ });
  await filmingTrigger.click();
  const filmingDialog = page
    .getByRole('dialog')
    .filter({ has: page.getByRole('heading', { name: '촬영일 기간' }) });
  await filmingDialog.getByRole('button', { name: '최근 3년' }).click();
  await expect(
    filmingDialog.getByRole('button', { name: '시작일 2023년 9월, 월 선택', exact: true }),
  ).toBeVisible();
  await expect(
    filmingDialog.locator('[data-endpoint="from"] [data-date="2023-09-11"]'),
  ).toHaveAttribute('tabindex', '0');
  await filmingDialog.getByRole('button', { name: '적용' }).click();
  await expect(
    page.getByRole('button', { name: '촬영일 기간 선택: 2023.09.11 – 2026.09.11' }),
  ).toBeVisible();

  await broadcastTrigger.click();
  await broadcastDialog.getByRole('button', { name: '초기화' }).click();
  await expect(broadcastDialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '방송일 기간 선택: 전체 기간' })).toBeVisible();
  await expect(
    page.getByRole('button', { name: '촬영일 기간 선택: 2023.09.11 – 2026.09.11' }),
  ).toBeVisible();
});

test('빈 검색어와 달력 날짜 선택 Enter는 검색을 시작하지 않는다', async ({ page }) => {
  await openAsEditor(page, '/search');

  const search = page.getByRole('search', { name: '뉴스 장면 검색' });
  const query = search.getByRole('searchbox', { name: '뉴스 장면 검색어' });
  const submit = search.getByRole('button', { name: '장면 찾기' });
  await query.fill('   ');
  await expect(submit).toBeDisabled();
  await query.press('Enter');
  await expect(page).toHaveURL(/\/search$/);

  await query.fill('날짜 입력 확인');
  await page.getByRole('button', { name: /^방송일 기간 선택:/ }).click();
  const dialog = page
    .getByRole('dialog')
    .filter({ has: page.getByRole('heading', { name: '방송일 기간' }) });
  const startDate = dialog.locator('[data-endpoint="from"] [data-date="2026-09-01"]');
  await startDate.focus();
  await startDate.press('Enter');

  await expect(dialog).toBeVisible();
  await expect(page).toHaveURL(/\/search$/);
});

test('결과 재검색은 동일 조건도 새 실행을 만들고 연속 제출을 한 번만 처리한다', async ({
  page,
}) => {
  await openAsEditor(page, `/search/results?q=${encodeURIComponent('기존 검색')}`);
  await expect(page.getByRole('button', { name: /1위 실제 응답 장면/ })).toBeVisible();

  let newSearchRequestCount = 0;
  let notifyRequest = () => {};
  const requestIntercepted = new Promise<void>((resolve) => {
    notifyRequest = resolve;
  });
  const blockedRoutes: Route[] = [];

  await page.route('**/search/results**', async (route) => {
    const url = new URL(route.request().url());
    const requestedQuery = url.searchParams.get('q');
    if (requestedQuery !== '새 검색') {
      await route.continue();
      return;
    }

    newSearchRequestCount += 1;
    blockedRoutes.push(route);
    notifyRequest();
  });

  const query = page.getByRole('textbox', { name: '뉴스 장면 검색어' });
  const searchForm = query.locator('xpath=ancestor::form');
  const submit = searchForm.getByRole('button');

  let repeatedSearchRequestCount = 0;
  let notifyRepeatedSearch = () => {};
  const repeatedSearchIntercepted = new Promise<void>((resolve) => {
    notifyRepeatedSearch = resolve;
  });
  const repeatedSearchRoutes: Route[] = [];
  await page.route('**/api/v1/search', (route) => {
    repeatedSearchRequestCount += 1;
    repeatedSearchRoutes.push(route);
    notifyRepeatedSearch();
  });

  await submit.evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await repeatedSearchIntercepted;
  await page.waitForTimeout(100);
  expect(repeatedSearchRequestCount).toBe(1);
  // S15P21A501-251 이후 동일 조건 재조회는 기존 결과를 유지합니다.
  // 버튼 표시와 별개로 재조회 중 연속 제출은 요청을 추가로 만들면 안 됩니다.
  await expect(submit).toBeEnabled();
  await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
  await submit.click();
  await page.waitForTimeout(100);
  expect(repeatedSearchRequestCount).toBe(1);

  const [repeatedSearchRoute] = repeatedSearchRoutes.splice(0, 1);
  if (!repeatedSearchRoute) throw new Error('Expected one repeated search request.');
  await repeatedSearchRoute.continue();
  await page.unroute('**/api/v1/search');
  await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();

  await query.fill('새 검색');
  try {
    await submit.evaluate((button: HTMLButtonElement) => {
      button.click();
      button.click();
    });

    await requestIntercepted;
    await page.waitForTimeout(100);
    expect(newSearchRequestCount).toBe(1);
    await expect(submit).toBeDisabled();
    await expect(page.getByRole('heading', { name: '검색 중', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /1위 실제 응답 장면/ })).toHaveCount(0);

    const [blockedRoute] = blockedRoutes.splice(0, 1);
    if (!blockedRoute) throw new Error('Expected one blocked search request.');
    await blockedRoute.continue();
    await expect(page).toHaveURL((url) => url.searchParams.get('q') === '새 검색');
    await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
  } finally {
    await Promise.all(repeatedSearchRoutes.map((route) => route.abort().catch(() => {})));
    await Promise.all(blockedRoutes.map((route) => route.abort().catch(() => {})));
  }
});
