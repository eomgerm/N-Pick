import { expect, test, type Page } from '@playwright/test';

const storageKey = 'npick:parallax-enabled';
const staticOffsets = Array(3).fill({ x: 0, y: 0 });

function toggle(page: Page) {
  return page.getByRole('switch', { name: '배경 움직임', exact: true });
}

async function offsets(page: Page) {
  return page.locator('[data-depth]').evaluateAll((elements) =>
    elements.map((element) => {
      const transform = getComputedStyle(element).transform;
      const matrix = new DOMMatrix(transform === 'none' ? undefined : transform);
      return { x: matrix.m41, y: matrix.m42 };
    }),
  );
}

async function openBackdrop(page: Page, path = '/login') {
  await page.goto(path);
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-ready',
    'true',
  );
  await expect(toggle(page)).toBeEnabled();
}

test('로그인·로그아웃의 전체 문서 이동과 검색·새로고침에도 선택을 공유한다', async ({
  page,
  context,
}, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route('**/api/v1/auth/login', async (route) => {
    await context.addCookies([
      { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
    ]);
    await route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: { memberId: '1', loginId: 'e2e-editor', role: 'EDITOR' },
      },
    });
  });
  await page.route('**/api/v1/auth/logout', async (route) => {
    await context.clearCookies({ name: 'JSESSIONID' });
    await route.fulfill({ json: { isSuccess: true, code: 'COMM_200', message: '성공' } });
  });
  await openBackdrop(page);
  await expect(toggle(page)).toBeChecked();
  await page.getByLabel('아이디', { exact: true }).fill('e2e-editor');
  await page.getByLabel('비밀번호', { exact: true }).fill('test-password');
  await toggle(page).focus();
  await page.keyboard.press('Space');
  await expect(toggle(page)).not.toBeChecked();
  await expect(toggle(page)).toBeFocused();
  await expect(page.getByLabel('아이디', { exact: true })).toHaveValue('e2e-editor');
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('test-password');
  await page.screenshot({ path: testInfo.outputPath('login-toggle-desktop.png') });
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page).toHaveURL(/\/search$/);
  await expect(toggle(page)).not.toBeChecked();
  await expect.poll(() => offsets(page)).toEqual(staticOffsets);
  await page.reload();
  await expect(toggle(page)).toBeEnabled();
  await expect(toggle(page)).not.toBeChecked();
  await page.screenshot({ path: testInfo.outputPath('search-toggle-desktop.png') });

  await toggle(page).click();
  await expect(toggle(page)).toBeChecked();
  await toggle(page).click();
  await page.getByRole('searchbox', { name: '뉴스 장면 검색어' }).fill('비 내리는 출근길');
  await page.getByRole('button', { name: '장면 찾기', exact: true }).click();
  await expect(page).toHaveURL(/\/search\/results\?q=/);
  await expect(page.locator('[data-static-mountain-backdrop]')).toHaveCount(1);
  await expect(toggle(page)).toHaveCount(0);
  await page.goBack();
  await expect(toggle(page)).toBeEnabled();
  await expect(toggle(page)).not.toBeChecked();
  await page.getByRole('button', { name: 'e2e-editor', exact: true }).click();
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?/);
  await expect(toggle(page)).toBeEnabled();
  await expect(toggle(page)).not.toBeChecked();
  await toggle(page).focus();
  await page.keyboard.press('Enter');
  await expect(toggle(page)).toBeChecked();
  await page.goto('/search');
  await expect(page).toHaveURL(/\/login\?/);
  await expect(toggle(page)).toBeChecked();
  expect(errors).toEqual([]);
});

test('움직이는 중 끄면 중앙에 고정하고 포인터·스크롤·창 크기 변경에도 정지한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 500 });
  await openBackdrop(page);
  await page.mouse.move(10, 10);
  await expect.poll(async () => (await offsets(page))[2].x).toBeGreaterThan(5);
  await toggle(page).click();
  await expect.poll(() => offsets(page)).toEqual(staticOffsets);
  await page.mouse.move(380, 480);
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.evaluate(() =>
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }),
  );
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await page.setViewportSize({ width: 400, height: 520 });
  await page.waitForTimeout(250);
  expect(await offsets(page)).toEqual(staticOffsets);
  await toggle(page).click();
  await page.mouse.move(10, 10);
  await expect.poll(async () => (await offsets(page))[2].x).toBeGreaterThan(5);
});

test('시스템 모션 감소는 선택을 덮어쓰지 않고 우선 적용한다', async ({ page }) => {
  await openBackdrop(page);
  for (const isEnabled of [true, false]) {
    if (!isEnabled) await toggle(page).click();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await expect(toggle(page)).toBeDisabled();
    await expect(toggle(page)).not.toBeChecked();
    await expect(toggle(page)).toHaveAccessibleDescription(/모션 감소/);
    await page.mouse.move(10, 10);
    await expect.poll(() => offsets(page)).toEqual(staticOffsets);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await expect(toggle(page)).toBeEnabled();
    await expect(toggle(page)).toBeChecked({ checked: isEnabled });
  }
});

for (const failure of ['read', 'write'] as const) {
  test(`저장소 ${failure} 실패에도 토글과 배경 제어가 동작한다`, async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(
      ({ key, failure }) => {
        localStorage.setItem(key, 'true');
        if (failure === 'read') {
          Object.defineProperty(window, 'localStorage', {
            get() {
              throw new DOMException('Storage blocked', 'SecurityError');
            },
          });
        } else {
          const original = Storage.prototype.setItem;
          Storage.prototype.setItem = function (name, value) {
            if (name === key) throw new DOMException('Storage full', 'QuotaExceededError');
            original.call(this, name, value);
          };
        }
      },
      { key: storageKey, failure },
    );
    await openBackdrop(page);
    await expect(toggle(page)).toBeChecked();
    await toggle(page).click();
    await expect(toggle(page)).not.toBeChecked();
    await page.mouse.move(10, 10);
    await page.waitForTimeout(200);
    expect(await offsets(page)).toEqual(staticOffsets);
    await toggle(page).click();
    await expect(toggle(page)).toBeChecked();
    await page.mouse.move(20, 20);
    await expect.poll(async () => (await offsets(page))[2].x).toBeGreaterThan(5);
    expect(errors).toEqual([]);
  });
}

test('로그인과 검색을 다른 탭에 열어도 선택이 양방향으로 동기화된다', async ({ page, context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
  await openBackdrop(page);
  const searchPage = await context.newPage();
  await openBackdrop(searchPage, '/search');
  await toggle(page).click();
  await expect(toggle(searchPage)).not.toBeChecked();
  await expect.poll(() => offsets(searchPage)).toEqual(staticOffsets);
  await toggle(searchPage).click();
  await expect(toggle(page)).toBeChecked();
  await page.reload();
  await expect(toggle(page)).toBeChecked();
});

for (const path of ['/login', '/search']) {
  test(`${path}: 작은 화면에서 토글과 시스템 설정 안내가 잘리지 않는다`, async ({
    page,
    context,
  }, testInfo) => {
    await context.addCookies([
      { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
    ]);
    await page.setViewportSize({ width: 320, height: 568 });
    await openBackdrop(page, path);
    await toggle(page).scrollIntoViewIfNeeded();
    await expect(toggle(page)).toBeInViewport();
    await toggle(page).click();
    await expect(toggle(page)).not.toBeChecked();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
    await page.setViewportSize({ width: 390, height: 844 });
    await toggle(page).scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
    await page.screenshot({ path: testInfo.outputPath('toggle-mobile.png'), fullPage: true });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await expect(toggle(page)).toBeDisabled();
    await expect(toggle(page)).toHaveAccessibleDescription(/모션 감소/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.screenshot({
      path: testInfo.outputPath('toggle-reduced-mobile.png'),
      fullPage: true,
    });
    if (path === '/search') {
      for (const viewport of [
        { width: 667, height: 320 },
        { width: 844, height: 390 },
      ]) {
        await page.setViewportSize(viewport);
        await toggle(page).scrollIntoViewIfNeeded();
        const searchBounds = (await page.getByRole('search').boundingBox())!;
        const toggleBounds = (await toggle(page).boundingBox())!;
        expect(toggleBounds.y).toBeGreaterThanOrEqual(searchBounds.y + searchBounds.height);
        expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
          viewport.width,
        );
        await page.screenshot({
          path: testInfo.outputPath(`toggle-landscape-${viewport.width}.png`),
          fullPage: true,
        });
      }
    }
  });
}
