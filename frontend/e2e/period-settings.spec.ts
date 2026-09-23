import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';

import { chooseDateBasis, openDatePicker, periodTrigger } from './date-picker-helpers';

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
  await page.clock.setFixedTime(new Date('2026-09-22T03:00:00Z'));
});

for (const width of [1440, 390]) {
  test(`${width}px 통합 기간 설정은 기준별 초안을 보존하고 두 필터를 함께 적용한다`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/search');
    await expect(page.locator('#search-tool-nav').getByRole('button')).toHaveCount(3);
    const dialog = await openDatePicker(page);
    const basis = dialog.getByRole('button', { name: '기준 선택', exact: true });
    const preset = dialog.getByRole('button', { name: '최근 1년', exact: true });
    const basisBox = (await basis.boundingBox())!;
    const presetBox = (await preset.boundingBox())!;
    expect(presetBox.x).toBeGreaterThan(basisBox.x + basisBox.width);
    expect(Math.abs(presetBox.y - basisBox.y)).toBeLessThan(3);
    await preset.click();
    await basis.click();
    await expect(dialog.getByRole('listbox', { name: '기준 선택' })).toBeVisible();
    await dialog.screenshot({ path: test.info().outputPath('period-basis-menu.png') });
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(basis).toHaveText('촬영일');
    await dialog.getByRole('button', { name: '최근 2년', exact: true }).click();
    await chooseDateBasis(page, '방송일');
    await expect(preset).toHaveAttribute('aria-pressed', 'true');
    await expect(periodTrigger(page)).toHaveAccessibleName('기간 설정: 전체 기간');
    await dialog.screenshot({ path: test.info().outputPath('period-header.png') });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(periodTrigger(page)).toHaveAccessibleName(
      '기간 설정: 방송일 2025.09.22 – 2026.09.22 · 촬영일 2024.09.22 – 2026.09.22',
    );
    await expect(
      page.getByRole('complementary', { name: '검색 도구', exact: true }),
    ).toHaveAttribute('data-expanded', 'false');
    await page.getByRole('searchbox').fill('기간 검색');
    await page.getByRole('button', { name: '장면 찾기', exact: true }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get('broadcastFrom') === '2025-09-22' &&
        url.searchParams.get('filmingFrom') === '2024-09-22',
    );
  });
}

test('사이드바 메뉴 설명은 커스텀 툴팁으로 표시하고 포커스에서도 읽을 수 있다', async ({
  page,
}) => {
  await page.goto('/search');
  for (const [name, description] of [
    [/^기간 설정:/, '기간 설정'],
    [/^이전 검색 기록$/, '이전 검색 기록'],
    [/^문의 사항$/, '문의 사항'],
  ] as const) {
    const button = page.getByRole('button', { name });
    await button.hover();
    const tooltip = page.getByRole('tooltip');
    await expect(tooltip).toHaveText(description);
    await expect(tooltip).toHaveCSS('border-radius', '12px');
    expect(await button.getAttribute('title')).toBeNull();
    await page.mouse.move(1200, 850);
    await expect(tooltip).toHaveCount(0);
    await button.focus();
    await expect(tooltip).toHaveText(description);
    await page.keyboard.press('Escape');
    await expect(tooltip).toHaveCount(0);
  }
});

async function clickUnavailable(page: Page, button: Locator) {
  await expect(button).toBeDisabled();
  const target = button.locator('..');
  await target.hover();
  await expect(target).toHaveCSS('cursor', 'default');
  await target.click();
  await expect(page.getByRole('tooltip')).toHaveText('해당 기간은 선택할 수 없어요');
  await page.screenshot({ path: test.info().outputPath('unavailable-period-tooltip.png') });
  await page.mouse.move(1200, 850);
  await expect(page.getByRole('tooltip')).toHaveCount(0);
}

test('마지막 달로 정상 이동하면 툴팁이 열리지 않고 비활성 버튼을 다시 눌러야 표시한다', async ({
  page,
}) => {
  await page.goto('/search');
  const dialog = await openDatePicker(page);
  const start = dialog.locator('[data-endpoint="from"]');
  await start.getByRole('button', { name: '시작일 이전 달', exact: true }).click();
  const next = start.getByRole('button', { name: '시작일 다음 달', exact: true });
  await expect(next).toBeEnabled();
  await next.click();
  await expect(next).toBeDisabled();
  await expect(page.getByRole('tooltip')).toHaveCount(0);
  await clickUnavailable(page, next);
});

test('미래 일·월·연도와 1950년 이전 탐색을 막고 클릭 시 이유를 표시한다', async ({ page }) => {
  await page.goto('/search');
  const dialog = await openDatePicker(page);
  const start = dialog.locator('[data-endpoint="from"]');
  await clickUnavailable(page, start.locator('[data-date="2026-09-23"]'));
  await expect(start.locator('[data-date="2026-09-23"]')).toHaveAttribute('aria-pressed', 'false');
  await start.getByRole('button', { name: /월 선택/ }).click();
  await clickUnavailable(page, start.getByRole('button', { name: '10월', exact: true }));
  await start.getByRole('button', { name: /연도 선택/ }).click();
  await clickUnavailable(page, start.getByRole('button', { name: '2027년', exact: true }));
  for (let index = 0; index < 7; index++)
    await start.getByRole('button', { name: '시작일 이전 연도 범위', exact: true }).click();
  await clickUnavailable(
    page,
    start.getByRole('button', { name: '시작일 이전 연도 범위', exact: true }),
  );
  await start.getByRole('button', { name: '1950년', exact: true }).click();
  await start.getByRole('button', { name: '1월', exact: true }).click();
  const first = start.locator('[data-date="1950-01-01"]');
  await expect(first).toBeEnabled();
  await first.focus();
  await page.keyboard.press('ArrowLeft');
  await expect(first).toBeFocused();
  await clickUnavailable(page, start.getByRole('button', { name: '시작일 이전 달', exact: true }));
  await first.click();
  await expect(first).toHaveAttribute('aria-pressed', 'true');
});

test('1950년 이전 URL은 검색하지 않고 기간 설정에서 수정한다', async ({ page }) => {
  let searches = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/search')) searches++;
  });
  await page.goto('/search/results?q=뉴스&broadcastFrom=1949-12-31&broadcastTo=1950-01-01');
  await expect(
    page.getByText('1950년 1월 1일 이전 날짜는 선택할 수 없습니다.').first(),
  ).toBeVisible();
  expect(searches).toBe(0);
  const dialog = await openDatePicker(page);
  await dialog.getByRole('button', { name: '최근 1년', exact: true }).click();
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
  expect(searches).toBe(1);
});

test('영상 등록은 달력과 직접 입력 모두 1950년 하한을 지킨다', async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
  ]);
  let uploads = 0;
  await page.route('**/api/v1/clips', (route) => {
    uploads++;
    return route.fulfill({ status: 500 });
  });
  await page.goto('/review?view=upload');
  await expect(page.locator('#broadcast-date')).toHaveAttribute('min', '1950-01-01');
  await expect(page.locator('#filmed-date')).toHaveAttribute('min', '1950-01-01');
  await page.locator('#video-file').setInputFiles({
    name: '영상.mp4',
    mimeType: 'video/mp4',
    buffer: readFileSync('e2e/preview-fixture.mp4'),
  });
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.locator('#broadcast-date').fill('1949-12-31');
  await page.locator('#filmed-date').fill('1949-12-31');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page.locator('#broadcast-date-error')).toHaveText(
    '방송일은 1950년 1월 1일 이전 날짜로 입력할 수 없습니다.',
  );
  await expect(page.locator('#filmed-date-error')).toHaveText(
    '촬영일은 1950년 1월 1일 이전 날짜로 입력할 수 없습니다.',
  );
  expect(uploads).toBe(0);
  await page.locator('#broadcast-date').fill('1950-01-01');
  await page.locator('#filmed-date').fill('1950-01-01');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect.poll(() => uploads).toBe(1);
});
