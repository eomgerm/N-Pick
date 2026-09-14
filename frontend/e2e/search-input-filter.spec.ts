import { expect, test, type Page, type Route } from '@playwright/test';

async function openAsEditor(page: Page, path: string) {
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
  const dialog = page.getByRole('dialog', { name: `${label}로 장면 찾기` });
  await dialog.getByLabel('시작일').fill(from);
  await dialog.getByLabel('종료일').fill(to);
  await dialog.getByRole('button', { name: '적용' }).click();
  await expect(dialog).not.toBeVisible();
}

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

test('빈 검색어와 날짜 입력 Enter는 검색을 시작하지 않는다', async ({ page }) => {
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
  const dialog = page.getByRole('dialog', { name: '방송일로 장면 찾기' });
  const startDate = dialog.getByRole('textbox', { name: '시작일' });
  await startDate.fill('2026-09-01');
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
  await expect(submit).toBeDisabled();
  await expect(page.getByRole('heading', { name: '검색 중', exact: true })).toBeVisible();

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
