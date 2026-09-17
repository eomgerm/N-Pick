import { expect, test, type Page } from '@playwright/test';

async function openSearch(page: Page) {
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
  await page.goto('/search');
  await page.getByRole('searchbox', { name: '뉴스 장면 검색어' }).fill('명절 교통');
}

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 390, height: 844 },
]) {
  test(`검색바가 원래 위치에서 결과 헤더까지 이동한다 (${viewport.width}px)`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openSearch(page);
    const origin = await page.getByRole('search').locator('> div').boundingBox();
    expect(origin).not.toBeNull();
    await page.getByRole('button', { name: '장면 찾기' }).click();

    // Freeze the real browser animation to measure its start, midpoint and destination.
    await page.waitForFunction(() => {
      const field = document.querySelector(
        'header input[aria-label="뉴스 장면 검색어"]',
      )?.parentElement;
      const animation = field?.getAnimations()[0];
      if (!animation) return false;
      animation.pause();
      animation.currentTime = 0;
      return true;
    });
    const field = page.getByRole('textbox', { name: '뉴스 장면 검색어' }).locator('..');
    const start = await field.boundingBox();
    for (const key of ['x', 'y', 'width', 'height'] as const) {
      expect(start![key]).toBeCloseTo(origin![key], 0);
    }
    await field.evaluate((element) => {
      element.getAnimations()[0].currentTime = 200;
    });
    const middle = await field.boundingBox();
    await page.screenshot({ path: test.info().outputPath('search-transition-middle.png') });
    await field.evaluate((element) => element.getAnimations()[0].finish());
    const destination = await field.boundingBox();
    expect(middle!.y).toBeLessThan(start!.y);
    expect(middle!.y).toBeGreaterThan(destination!.y);
    expect(destination!.y).toBeLessThan(100);
    expect(destination!.x).toBeGreaterThanOrEqual(0);
    expect(destination!.x + destination!.width).toBeLessThanOrEqual(viewport.width);
    await expect(page.getByRole('textbox', { name: '뉴스 장면 검색어' })).toHaveValue('명절 교통');
    await expect(page.getByRole('button', { name: /1위 실제 응답 장면/ })).toBeVisible();
    await page.screenshot({ path: test.info().outputPath('search-transition-complete.png') });

    // Searching again in the results header must not replay the entry motion.
    await page.getByRole('textbox', { name: '뉴스 장면 검색어' }).fill('새 검색');
    await page.getByRole('button', { name: '검색', exact: true }).click();
    await expect(page).toHaveURL((url) => url.searchParams.get('q') === '새 검색');
    await expect(page.getByRole('button', { name: /1위 실제 응답 장면/ })).toBeVisible();
    expect(await field.evaluate((element) => element.getAnimations().length)).toBe(0);
  });
}

test('모션 감소 설정에서는 검색바를 즉시 헤더에 배치한다', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openSearch(page);
  await page.getByRole('button', { name: '장면 찾기' }).click();
  const field = page.getByRole('textbox', { name: '뉴스 장면 검색어' }).locator('..');
  await expect(field).toBeVisible();
  expect(await field.evaluate((element) => element.getAnimations().length)).toBe(0);
  expect((await field.boundingBox())!.y).toBeLessThan(100);
});

test('검색 실패 요약의 아이콘과 제목, 설명을 가운데 정렬한다', async ({ page }) => {
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      status: 503,
      json: { isSuccess: false, code: 'COMM_503', message: '연결 오류' },
    }),
  );
  await openSearch(page);
  await page.getByRole('button', { name: '장면 찾기' }).click();
  const summary = page
    .getByRole('heading', { name: '검색 결과를 불러오지 못했어요' })
    .locator('..');
  await expect(summary).toHaveCSS('text-align', 'center');
  await expect(summary).toHaveCSS('justify-items', 'center');
  await page.screenshot({
    path: test.info().outputPath('search-failed-centered.png'),
    animations: 'disabled',
  });
});
