import { expect, test, type Page } from '@playwright/test';

async function offsets(page: Page) {
  return page.locator('[data-depth]').evaluateAll((elements) =>
    elements.map((element) => {
      const transform = getComputedStyle(element).transform;
      const matrix = new DOMMatrix(transform === 'none' ? undefined : transform);
      return { x: matrix.m41, y: matrix.m42 };
    }),
  );
}

async function openLandscape(page: Page) {
  await page.goto('/login?role=editor');
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-ready',
    'true',
  );
}

test('로그인 세 레이어가 다른 깊이로 움직이고 폼을 사용할 수 있다', async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 900 });
  await openLandscape(page);
  await expect(page.locator('canvas[data-metaballs]')).toHaveCount(0);
  await page.mouse.move(1400, 860);
  await expect.poll(async () => (await offsets(page))[2].x).toBeLessThan(-18);
  const right = await offsets(page);
  expect(Math.abs(right[0].x)).toBeLessThan(Math.abs(right[1].x));
  expect(Math.abs(right[1].x)).toBeLessThan(Math.abs(right[2].x));
  await page.mouse.move(40, 40);
  await expect.poll(async () => (await offsets(page))[2].x).toBeGreaterThan(18);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByLabel('아이디', { exact: true })).toBeFocused();
  await expect(page.getByText('편집자 ID를 입력해 주세요.', { exact: true })).toBeVisible();
  await page.getByLabel('아이디', { exact: true }).fill('editor');
  await page.getByLabel('비밀번호', { exact: true }).fill('example');
  await page.mouse.move(720, 450);
  await expect.poll(async () => Math.abs((await offsets(page))[2].x)).toBeLessThan(0.02);
  await page.screenshot({ path: testInfo.outputPath('login-mountains-desktop.png') });
  await page.getByRole('link', { name: '역할 다시 선택' }).click();
  await expect(page).toHaveURL(/\/landing$/);
  await expect(page.locator('[data-mountain-backdrop]')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('작은 화면의 실제 스크롤에 반응하며 가로 넘침과 배경 틈이 없다', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 500 });
  await openLandscape(page);
  await page.evaluate(() =>
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }),
  );
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await expect.poll(async () => (await offsets(page))[2].y).toBeGreaterThan(1);
  const scrollOffsets = await offsets(page);
  expect(scrollOffsets[2].y).toBeGreaterThan(scrollOffsets[1].y);
  const bounds = await page.locator('[data-depth="foreground"]').boundingBox();
  expect(bounds!.x).toBeLessThanOrEqual(0);
  expect(bounds!.y).toBeLessThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeGreaterThanOrEqual(390);
  expect(bounds!.y + bounds!.height).toBeGreaterThanOrEqual(500);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.getByRole('button', { name: '로그인', exact: true }).scrollIntoViewIfNeeded();
  await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeInViewport();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: testInfo.outputPath('login-mountains-mobile.png'),
    fullPage: true,
  });
});

test('모션 감소 설정과 숨긴 탭은 움직임을 멈춘다', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openLandscape(page);
  await page.mouse.move(100, 100);
  expect(await offsets(page)).toEqual(Array(3).fill({ x: 0, y: 0 }));
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-motion',
    'active',
  );
  await page.mouse.move(10, 10);
  await expect.poll(async () => (await offsets(page))[2].x).toBeGreaterThan(5);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const paused = await offsets(page);
  await page.mouse.move(900, 500);
  await page.waitForTimeout(200);
  expect(await offsets(page)).toEqual(paused);
  await page.evaluate(() => {
    Reflect.deleteProperty(document, 'hidden');
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect.poll(() => offsets(page)).toEqual(Array(3).fill({ x: 0, y: 0 }));
});

test('터치 포인터는 마우스 움직임을 합성하지 않는다', async ({ page }) => {
  await openLandscape(page);
  await page.evaluate(() => {
    window.dispatchEvent(
      new PointerEvent('pointermove', { pointerType: 'touch', clientX: 10, clientY: 10 }),
    );
  });
  await page.waitForTimeout(200);
  expect(await offsets(page)).toEqual(Array(3).fill({ x: 0, y: 0 }));
});

test('레이어 로딩 실패는 원본 사진으로 대체하고 검수자 폼을 유지한다', async ({ page }) => {
  await page.route('**/login-mountains/mountains.webp', (route) => route.abort());
  await page.goto('/login?role=reviewer');
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-ready',
    'false',
  );
  await expect(page.locator('img[src="/images/login-mountains/original.webp"]')).toHaveJSProperty(
    'complete',
    true,
  );
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByText('검수자 ID를 입력해 주세요.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('아이디', { exact: true })).toBeFocused();
});
