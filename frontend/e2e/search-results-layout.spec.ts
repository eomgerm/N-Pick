import { expect, test } from '@playwright/test';

for (const width of [1440, 390]) {
  test(`검색 결과 공통 메뉴와 사이드바를 ${width}px에서 사용한다`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
    await page.goto('/search/results?q=장면&broadcastFrom=2026-09-01&broadcastTo=2026-09-03');
    await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
    const header = page.getByRole('banner');
    const searchInput = header.getByRole('textbox', { name: '뉴스 장면 검색어' });
    const account = header.getByRole('button', { name: /e2e-editor/ });
    const initialAccountBounds = (await account.boundingBox())!;
    const initialSearchBounds = (await searchInput.boundingBox())!;
    await expect(header).toHaveCSS('position', 'fixed');
    expect(initialSearchBounds.y + initialSearchBounds.height / 2).toBeCloseTo(
      initialAccountBounds.y + initialAccountBounds.height / 2,
      0,
    );

    const sidebar = page.getByRole('complementary', { name: '검색 도구', exact: true });
    await expect(sidebar).toHaveCount(1);
    await expect(
      sidebar.getByRole('button', { name: '방송일 기간 선택: 2026.09.01 – 2026.09.03' }),
    ).toBeVisible();
    expect((await sidebar.boundingBox())!.x + (await sidebar.boundingBox())!.width).toBeLessThan(
      (await page.getByRole('main').boundingBox())!.x,
    );
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await page.screenshot({ path: testInfo.outputPath('results.png'), fullPage: true });

    await account.click();
    const menu = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect(menu.getByRole('link', { name: '장면 검색' })).toBeVisible();
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeInViewport();
    await page.keyboard.press('Escape');
    await expect(account).toBeFocused();
    await expect(menu).not.toBeVisible();

    await sidebar.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
    const history = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
    await expect(history.getByRole('region', { name: '이전 검색 기록 목록' })).toBeVisible();
    await history.getByRole('button', { name: '정보 패널 닫기' }).click();
    await expect(history).not.toBeVisible();

    await page.route('**/api/v1/search', (route) =>
      route.fulfill({
        status: 404,
        json: {
          isSuccess: false,
          code: 'COMM_404',
          message: '요청한 항목을 찾을 수 없습니다.',
          requestId: 'layout-error',
        },
      }),
    );
    await page.getByRole('button', { name: '검색', exact: true }).click();
    await page.getByRole('button', { name: '오류 알림 닫기' }).click();
    await expect(
      page.getByRole('heading', { name: '검색 결과를 불러오지 못했어요' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '같은 조건으로 다시 시도' })).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath('results-frosted-error.png'),
      fullPage: true,
    });
    await searchInput.click();
    await expect(page.getByRole('textbox', { name: '뉴스 장면 검색어' })).toBeFocused();
    await expect(page).toHaveURL((url) => url.searchParams.get('broadcastFrom') === '2026-09-01');
    const statePanel = page.locator('[role="alert"][data-state="failed"]');
    const stateBounds = (await statePanel.boundingBox())!;
    const sidebarBounds = (await sidebar.boundingBox())!;
    expect(stateBounds.y + stateBounds.height).toBeCloseTo(
      sidebarBounds.y + sidebarBounds.height,
      0,
    );
    await statePanel.evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    expect((await account.boundingBox())!.y).toBeCloseTo(initialAccountBounds.y, 0);
    expect((await searchInput.boundingBox())!.y).toBeCloseTo(initialSearchBounds.y, 0);
    await expect(searchInput).toBeInViewport();
    await page.screenshot({ path: testInfo.outputPath('results-error-scrolled.png') });
    await account.click();
    await expect(menu).toBeVisible();
    await page.keyboard.press('Escape');
    await searchInput.click();
    await expect(menu).not.toBeVisible();
    await expect(searchInput).toBeFocused();
  });
}
