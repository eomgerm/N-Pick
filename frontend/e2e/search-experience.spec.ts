import { expect, test, type Locator } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.clock.setFixedTime(new Date('2026-09-16T03:00:00Z'));
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
});

test('검색도 세 산 레이어가 깊이별로 움직이며 모션 감소에서는 정지한다', async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript(() => localStorage.setItem('npick:parallax-enabled', 'false'));
  await page.goto('/search');
  await expect(page.getByRole('switch', { name: '배경 움직임' })).toHaveCount(0);
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-ready',
    'true',
  );
  await expect(page.locator('canvas, [data-static-mountain-backdrop]')).toHaveCount(0);
  await expect(page.getByRole('search').locator('> div').first()).toHaveCSS(
    'backdrop-filter',
    /blur\(/,
  );
  const intro = page.getByRole('heading', { name: '안녕하세요.' }).locator('..');
  await expect(intro).toHaveCSS('animation-duration', '1s');
  await expect(intro).toHaveCSS('opacity', '1');
  const offsets = () =>
    page
      .locator('[data-depth]')
      .evaluateAll((layers) =>
        layers.map((layer) => new DOMMatrix(getComputedStyle(layer).transform).m41),
      );
  await page.mouse.move(1400, 860);
  await expect.poll(async () => (await offsets())[2]).toBeLessThan(-18);
  const layers = await offsets();
  expect(Math.abs(layers[0])).toBeLessThan(Math.abs(layers[1]));
  expect(Math.abs(layers[1])).toBeLessThan(Math.abs(layers[2]));
  for (const height of [650, 900]) {
    await page.setViewportSize({ width: 1440, height });
    const searchBounds = (await page.getByRole('search').boundingBox())!;
    expect(searchBounds.y + searchBounds.height / 2).toBeCloseTo(height / 2, 0);
  }
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect.poll(offsets).toEqual([0, 0, 0]);
  await expect(intro).toHaveCSS('animation-name', 'none');
  await expect(intro).toHaveCSS('opacity', '1');
  await page.screenshot({ path: testInfo.outputPath('search-desktop.png') });
  expect(errors).toEqual([]);
});

for (const role of ['editor', 'reviewer']) {
  test(`${role} 계정 이미지 오른쪽에 프로필과 역할별 메뉴가 펼쳐지고 Escape로 닫힌다`, async ({
    page,
  }, testInfo) => {
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: `e2e-${role}`, url: 'http://127.0.0.1:3116' }]);
    await page.goto('/search');
    const account = page.getByRole('button', { name: new RegExp(`e2e-${role}`) });
    const menu = page.getByRole('navigation', { name: '주요 메뉴' });
    const profile = page.getByText(`e2e-${role}`, { exact: true });
    const roleLabel = page.getByText(role === 'reviewer' ? '아카이브 팀' : '편집 기사', {
      exact: true,
    });
    await expect(page.getByRole('link', { name: 'N-Pick 홈' })).toHaveCount(0);
    await expect(account).toHaveAccessibleName(`e2e-${role}`);
    await expect(account).toHaveText('');
    expect((await account.boundingBox())!.width).toBe(72);
    expect((await account.boundingBox())!.height).toBe(72);
    await expect(account.locator('svg')).toHaveCount(0);
    expect((await account.boundingBox())!.x).toBeLessThan(72);
    expect((await account.boundingBox())!.y).toBeLessThan(100);
    await expect(profile).not.toBeVisible();
    await expect(roleLabel).not.toBeVisible();
    await expect(account).toHaveAttribute('aria-expanded', 'false');
    await expect(menu).not.toBeVisible();
    await account.click();
    await expect(profile).toBeVisible();
    await expect(roleLabel).toBeVisible();
    await expect(menu.getByRole('link', { name: '장면 검색' })).toBeVisible();
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toHaveCSS(
      'background-color',
      'rgb(197, 43, 61)',
    );
    await expect(menu.getByRole('link', { name: '검수', exact: true })).toHaveCount(
      role === 'reviewer' ? 1 : 0,
    );
    expect((await menu.locator('..').boundingBox())!.x).toBeGreaterThan(
      (await account.boundingBox())!.x + (await account.boundingBox())!.width,
    );
    expect((await menu.locator('..').boundingBox())!.y).toBeCloseTo(
      (await account.boundingBox())!.y,
      0,
    );
    await page.screenshot({ path: testInfo.outputPath('account-menu.png') });
    await page.keyboard.press('Escape');
    await expect(account).toBeFocused();
    await expect(menu).not.toBeVisible();
    await account.click();
    await page.getByRole('searchbox').click();
    await expect(menu).not.toBeVisible();
  });
}

async function sampleMorph(button: Locator) {
  return button.evaluate(async (element: HTMLButtonElement) => {
    const dock = element.closest('aside')!;
    const widths: number[] = [];
    const started = performance.now();
    element.click();
    while (performance.now() - started < 550) {
      widths.push(dock.getBoundingClientRect().width);
      await new Promise(requestAnimationFrame);
    }
    return widths;
  });
}

test('사이드바는 양방향으로 부드럽게 펼쳐지고 기록 패널은 같은 높이와 유리색을 사용한다', async ({
  page,
}, testInfo) => {
  await page.goto('/search');
  const dock = page.getByRole('complementary', { name: '검색 도구', exact: true });
  const historyButton = dock.getByRole('button', { name: '이전 검색 기록', exact: true });
  const widths = await sampleMorph(historyButton);
  expect(widths.some((width) => width > 73 && width < 223)).toBe(true);
  await expect(dock).toHaveCSS('width', '224px');
  const navSurface = page.locator('#search-tool-nav');
  const history = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  await expect(history).toHaveCSS('opacity', '1');
  const navBounds = (await navSurface.boundingBox())!;
  const panelBounds = (await history.boundingBox())!;
  expect(panelBounds.height).toBeCloseTo(navBounds.height, 0);
  expect(panelBounds.y).toBeCloseTo(navBounds.y, 0);
  const surface = await navSurface.evaluate((element) => {
    const style = getComputedStyle(element, '::before');
    return {
      background: style.backgroundImage,
      color: style.backgroundColor,
      blur: style.backdropFilter,
    };
  });
  const background = surface.background;
  expect(surface.blur).toMatch(/blur\(/);
  await expect(page.getByRole('button', { name: 'e2e-editor', exact: true })).toHaveCSS(
    'background-color',
    surface.color,
  );
  await expect(history).toHaveCSS('backdrop-filter', /blur\(/);
  await expect(history).toHaveCSS('background-image', background);
  await expect(history).toHaveCSS('background-color', surface.color);
  expect(background).toContain('frosted-grain.svg');
  await page.screenshot({ path: testInfo.outputPath('history-panel.png') });
  const closeWidths = await sampleMorph(historyButton);
  expect(closeWidths.some((width) => width > 73 && width < 223)).toBe(true);
  await expect(dock).toHaveCSS('width', '72px');
  await expect(history).not.toBeVisible();
  await expect(page.locator('#search-history-panel')).toHaveAttribute('inert', '');

  await dock.getByRole('button', { name: '문의 사항', exact: true }).click();
  const inquiry = page.getByRole('complementary', { name: '문의 사항', exact: true });
  await expect(inquiry).toHaveCSS('opacity', '1');
  expect((await inquiry.boundingBox())!.height).toBeCloseTo(navBounds.height, 0);
  await expect(inquiry).toHaveCSS('background-image', background);
  await inquiry.getByRole('button', { name: '정보 패널 닫기' }).click();
  await expect(dock.getByRole('button', { name: '문의 사항', exact: true })).toBeFocused();
  await expect(inquiry).not.toBeVisible();
});

test('월·연도 선택 후 왼쪽 시작일과 오른쪽 종료일을 독립적으로 적용한다', async ({
  page,
}, testInfo) => {
  await page.goto('/search');
  await page.getByRole('button', { name: /^방송일 기간 선택:/ }).click();
  const dialog = page.getByRole('dialog', { name: '방송일 기간', exact: true });
  await expect(dialog).toHaveCSS('background-image', /frosted-grain\.svg/);
  await expect(dialog).toHaveCSS('backdrop-filter', /blur\(/);
  await expect(dialog.getByRole('button', { name: '초기화', exact: true })).toHaveCSS(
    'text-decoration-line',
    'none',
  );
  const start = dialog.getByRole('region', { name: '시작일 선택', exact: true });
  const end = dialog.getByRole('region', { name: '종료일 선택', exact: true });
  expect((await start.boundingBox())!.x).toBeLessThan((await end.boundingBox())!.x);
  for (const [calendar, label, month] of [
    [start, '시작일', '2월'],
    [end, '종료일', '3월'],
  ] as const) {
    await calendar
      .getByRole('button', { name: `${label} 2026년 9월, 월 선택`, exact: true })
      .click();
    await calendar.getByRole('button', { name: `${label} 2026년, 연도 선택`, exact: true }).click();
    await expect(
      calendar.getByRole('button', { name: `${label} 2020 - 2030, 연도 범위`, exact: true }),
    ).toBeVisible();
    if (label === '시작일') {
      await page.screenshot({ path: testInfo.outputPath('date-years-desktop.png') });
    }
    await calendar.getByRole('button', { name: '2024년', exact: true }).click();
    await expect(calendar.getByRole('button', { name: '9월', exact: true })).toBeFocused();
    if (label === '시작일') {
      await page.screenshot({ path: testInfo.outputPath('date-months-desktop.png') });
    }
    await calendar.getByRole('button', { name: month, exact: true }).click();
  }
  await start.locator('[data-date="2024-02-29"]').click();
  await end.locator('[data-date="2024-03-02"]').click();
  await expect(start.locator('[data-date="2024-02-29"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(end.locator('[data-date="2024-03-02"]')).toHaveAttribute('aria-pressed', 'true');
  await page.screenshot({ path: testInfo.outputPath('date-range-desktop.png') });
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(
    page.getByRole('button', { name: '방송일 기간 선택: 2024.02.29 – 2024.03.02', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '촬영일 기간 선택: 전체 기간', exact: true }),
  ).toBeVisible();
  await page.getByRole('searchbox').fill('날짜 범위 검색');
  await page.getByRole('button', { name: '장면 찾기', exact: true }).click();
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === '/search/results' &&
      url.searchParams.get('broadcastFrom') === '2024-02-29' &&
      url.searchParams.get('broadcastTo') === '2024-03-02',
  );
});

test('사이드바에는 적용한 기간만 표시하고 즉시 초기화와 접힘 상태를 반영한다', async ({
  page,
}, testInfo) => {
  await page.goto('/search');
  for (const label of ['방송일', '촬영일']) {
    const trigger = page.getByRole('button', { name: new RegExp(`^${label} 기간 선택:`) });
    await trigger.click();
    const dialog = page.getByRole('dialog', { name: `${label} 기간`, exact: true });
    await dialog.locator('[data-endpoint="from"] [data-date="2026-09-01"]').click();
    await dialog.locator('[data-endpoint="to"] [data-date="2026-09-16"]').click();
    await expect(trigger.locator('time')).toHaveCount(0);
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(trigger.locator('time')).toHaveText(['2026.09.01', '2026.09.16']);
  }
  await page.screenshot({ path: testInfo.outputPath('selected-periods-desktop.png') });
  await page.getByRole('searchbox').click();
  const broadcast = page.getByRole('button', { name: /^방송일 기간 선택:/ });
  await expect(broadcast.locator('> span')).toHaveCSS('opacity', '0');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  const dock = page.getByRole('complementary', { name: '검색 도구', exact: true });
  await expect(dock).toHaveCSS('width', '168px');
  const right = (await dock.boundingBox())!.x + (await dock.boundingBox())!.width;
  for (const date of await dock.locator('button time').all()) {
    const box = (await date.boundingBox())!;
    expect(box.x + box.width).toBeLessThan(right);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath('selected-periods-mobile.png') });
  await broadcast.click();
  const dialog = page.getByRole('dialog', { name: '방송일 기간', exact: true });
  await dialog.getByRole('button', { name: '초기화', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(broadcast.locator('time')).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: /^촬영일 기간 선택:/ }).locator('time'),
  ).toHaveCount(2);
});

test('기간 검증·취소·초기화와 월 경계의 키보드 조작을 보존한다', async ({ page }) => {
  await page.goto('/search');
  const trigger = page.getByRole('button', { name: /^촬영일 기간 선택:/ });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: '촬영일 기간', exact: true });
  const start = dialog.locator('[data-endpoint="from"]');
  const end = dialog.locator('[data-endpoint="to"]');
  await start.locator('[data-date="2026-09-20"]').click();
  await end.locator('[data-date="2026-09-10"]').click();
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('종료일은 시작일과 같거나 이후여야 해요.');
  await expect(trigger).toHaveAccessibleName('촬영일 기간 선택: 전체 기간');
  await end.locator('[data-date="2026-09-20"]').click();
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(trigger).toHaveAccessibleName('촬영일 기간 선택: 2026.09.20 – 2026.09.20');
  await trigger.click();
  await start.locator('[data-date="2026-09-01"]').focus();
  await page.keyboard.press('ArrowLeft');
  await expect(start.locator('[data-date="2026-08-31"]')).toBeFocused();
  await page.keyboard.press('Enter');
  await dialog.getByRole('button', { name: '취소', exact: true }).click();
  await expect(trigger).toHaveAccessibleName('촬영일 기간 선택: 2026.09.20 – 2026.09.20');
  await trigger.click();
  await dialog.getByRole('button', { name: '초기화', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toHaveAccessibleName('촬영일 기간 선택: 전체 기간');
  await trigger.click();
  await start.locator('[data-date="2026-09-01"]').click();
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('시작일과 종료일을 모두 선택해 주세요.');
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await expect(dialog).not.toBeVisible();
});

test('모바일도 좌우 달력과 패널 높이를 유지하며 가로로 넘치지 않는다', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/search');
  await expect(page.locator('[data-mountain-backdrop] [data-ready]')).toHaveAttribute(
    'data-ready',
    'true',
  );
  const searchBounds = (await page.getByRole('search').boundingBox())!;
  expect(searchBounds.y + searchBounds.height / 2).toBeCloseTo(844 / 2, 0);
  await page.screenshot({ path: testInfo.outputPath('search-mobile.png') });
  await page.getByRole('button', { name: /^방송일 기간 선택:/ }).click();
  const dialog = page.getByRole('dialog', { name: '방송일 기간', exact: true });
  const startBounds = (await dialog.locator('[data-endpoint="from"]').boundingBox())!;
  const endBounds = (await dialog.locator('[data-endpoint="to"]').boundingBox())!;
  expect(startBounds.y).toEqual(endBounds.y);
  expect(startBounds.x + startBounds.width).toBeLessThan(endBounds.x);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath('date-range-mobile.png') });
  await dialog.getByRole('button', { name: '방송일 기간 선택 닫기' }).click();
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  const panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  expect((await panel.boundingBox())!.height).toBe(
    (await page.locator('#search-tool-nav').boundingBox())!.height,
  );
  expect(
    await panel.evaluate((element) => parseFloat(getComputedStyle(element).transitionDuration)),
  ).toBeLessThanOrEqual(0.00001);
  await page.screenshot({ path: testInfo.outputPath('history-mobile.png') });
  await panel.getByRole('button', { name: '정보 패널 닫기' }).click();
  await page.getByRole('button', { name: /e2e-editor/ }).click();
  await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeInViewport();
  await page.screenshot({ path: testInfo.outputPath('account-mobile.png') });
  await page.setViewportSize({ width: 320, height: 844 });
  await expect(page.getByRole('navigation', { name: '주요 메뉴' }).locator('..')).toBeInViewport({
    ratio: 1,
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
  await page.screenshot({ path: testInfo.outputPath('account-mobile-small.png') });
});

test('결과 화면의 공통 날짜 선택기도 모바일 안에 표시하고 필터 URL을 갱신한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/search/results?q=날짜');
  for (const label of ['방송일', '촬영일']) {
    await page.getByRole('button', { name: new RegExp(`^${label} 기간 선택:`) }).click();
    const dialog = page.getByRole('dialog', { name: `${label} 기간`, exact: true });
    const bounds = (await dialog.boundingBox())!;
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
    await dialog.locator('[data-endpoint="from"] [data-date="2026-09-01"]').click();
    await dialog.locator('[data-endpoint="to"] [data-date="2026-09-03"]').click();
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    const key = label === '방송일' ? 'broadcast' : 'filming';
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get(`${key}From`) === '2026-09-01' &&
        url.searchParams.get(`${key}To`) === '2026-09-03',
    );
  }
});
