import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' }]);
});

for (const [name, route] of [
  ['results', '/search/results?q=비%20내리는%20출근길'],
  ['processing', '/review?view=processing'],
]) {
  test(`${name}: 같은 정지 산 배경을 표시하고 물방울을 불러오지 않는다`, async ({
    page,
  }, testInfo) => {
    const graphicsRequests: string[] = [];
    page.on('request', (request) => {
      if (
        /\/textures\/metaballs\/|\.wasm(?:\?|$)|app-mountain-backdrop|original.webp/.test(
          request.url(),
        )
      ) {
        graphicsRequests.push(request.url());
      }
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(route);
    const backdrop = page.locator('[data-mountain-backdrop]');
    await expect(backdrop).toHaveCount(1);
    await expect(page.locator('[data-static-mountain-backdrop], canvas')).toHaveCount(0);
    await expect(backdrop.locator('[data-ready]')).toHaveAttribute('data-ready', 'true');
    await expect(backdrop.locator('[data-ready]')).toHaveAttribute('data-motion', 'static');
    await expect(page.locator('[data-backdrop-veil]')).toHaveCount(1);
    await expect(backdrop.locator('[data-depth]')).toHaveCount(3);
    const picture = backdrop.locator('[data-depth="foreground"]');
    const before = await picture.boundingBox();
    await page.mouse.move(10, 10);
    await page.mouse.move(1380, 820);
    await page.evaluate(() =>
      window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }),
    );
    await expect(picture).toHaveCSS('transform', 'none');
    expect(await picture.boundingBox()).toEqual(before);
    expect(
      await backdrop.evaluate((element) => element.getAnimations({ subtree: true }).length),
    ).toBe(0);
    expect(graphicsRequests).toEqual([]);
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    await page.screenshot({ path: testInfo.outputPath(`${name}-mountains.png`) });
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(backdrop).toHaveCSS('position', 'fixed');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.screenshot({ path: testInfo.outputPath(`${name}-mountains-mobile.png`) });
  });
}

test('파라랙스 검색 화면에서 제출하면 결과 화면은 같은 산의 정지 배경을 표시한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/search');
  await expect(page.locator('[data-ready]')).toHaveAttribute('data-ready', 'true');
  const backdrop = await page.locator('[data-mountain-backdrop]').elementHandle();
  await page.mouse.move(10, 10);
  await expect
    .poll(() =>
      page
        .locator('[data-depth="foreground"]')
        .evaluate((element) => new DOMMatrix(getComputedStyle(element).transform).m41),
    )
    .toBeGreaterThan(5);
  await page.getByRole('searchbox', { name: '뉴스 장면 검색어' }).fill('비 내리는 출근길');
  await page.getByRole('button', { name: '장면 찾기', exact: true }).click();
  await expect(page).toHaveURL(/\/search\/results\?q=/);
  await expect(page.locator('[data-mountain-backdrop]')).toHaveCount(1);
  await expect(page.locator('canvas, [data-static-mountain-backdrop]')).toHaveCount(0);
  expect(
    await backdrop!.evaluate(
      (element) => element === document.querySelector('[data-mountain-backdrop]'),
    ),
  ).toBe(true);
  await expect(page.locator('[data-ready]')).toHaveAttribute('data-motion', 'static');
  for (const layer of await page.locator('[data-depth]').all()) {
    await expect(layer).toHaveCSS('transform', 'none');
  }
  await page.goBack();
  await expect(page).toHaveURL(/\/search$/);
  await expect(page.locator('[data-ready]')).toHaveAttribute('data-motion', 'active');
  expect(
    await backdrop!.evaluate(
      (element) => element === document.querySelector('[data-mountain-backdrop]'),
    ),
  ).toBe(true);
  await page.mouse.move(1400, 800);
  await expect
    .poll(() =>
      page
        .locator('[data-depth="foreground"]')
        .evaluate((element) => new DOMMatrix(getComputedStyle(element).transform).m41),
    )
    .toBeLessThan(-5);
});
