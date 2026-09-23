import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
  ]);
  await page.clock.setFixedTime(new Date('2026-09-22T03:00:00Z'));
});

for (const width of [1440, 390]) {
  test(`${width}px 등록 달력은 방송일·촬영일의 범위를 지키고 선택 즉시 반영한다`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/review?view=upload');
    await expect(page.locator('input[type="date"]')).toHaveCount(0);
    await page.getByRole('button', { name: '촬영일 달력 열기', exact: true }).click();
    const filming = page.getByRole('dialog', { name: '촬영일 선택', exact: true });
    await expect(filming.locator('[data-date="2026-09-23"]')).toBeDisabled();
    await filming.locator('[data-date="2026-09-10"]').click();
    await expect(filming).not.toBeVisible();
    await expect(page.locator('#filmed-date')).toHaveValue('2026-09-10');
    await expect(page.locator('#filmed-date')).toBeFocused();

    await page.getByRole('button', { name: '방송일 달력 열기', exact: true }).click();
    const broadcast = page.getByRole('dialog', { name: '방송일 선택', exact: true });
    const unavailable = broadcast.locator('[data-date="2026-09-09"]');
    await expect(unavailable).toBeDisabled();
    await unavailable.locator('..').click();
    await expect(page.getByRole('tooltip')).toHaveText('해당 기간은 선택할 수 없어요');
    await expect(page.locator('#broadcast-date')).toHaveValue('');
    await expect(
      broadcast.getByRole('button', { name: '방송일 이전 달', exact: true }),
    ).toBeDisabled();
    await broadcast.getByRole('button', { name: /월 선택/ }).click();
    await expect(broadcast.getByRole('button', { name: '8월', exact: true })).toBeDisabled();
    await expect(broadcast.getByRole('button', { name: '9월', exact: true })).toBeEnabled();
    await broadcast.getByRole('button', { name: /연도 선택/ }).click();
    await expect(broadcast.getByRole('button', { name: '2025년', exact: true })).toBeDisabled();
    await broadcast.getByRole('button', { name: '2026년', exact: true }).click();
    await broadcast.getByRole('button', { name: '9월', exact: true }).click();
    const firstDay = broadcast.locator('[data-date="2026-09-10"]');
    await expect(firstDay).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(firstDay).toBeFocused();
    await broadcast.screenshot({ path: test.info().outputPath('registration-calendar.png') });
    const box = (await broadcast.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(900);
    await broadcast.locator('[data-date="2026-09-12"]').click();
    await expect(page.locator('#broadcast-date')).toHaveValue('2026-09-12');
    await page.getByRole('button', { name: '촬영일 달력 열기', exact: true }).click();
    await expect(filming.locator('[data-date="2026-09-13"]')).toBeDisabled();
    await filming.locator('[data-date="2026-09-12"]').click();
    await expect(page.locator('#filmed-date')).toHaveValue('2026-09-12');
    await page.locator('#filmed-date').press('ArrowDown');
    await filming.getByRole('button', { name: '날짜 지우기', exact: true }).click();
    await expect(page.locator('#filmed-date')).toHaveValue('');
    await expect(page.locator('#broadcast-date')).toHaveValue('2026-09-12');
  });
}

test('등록 달력은 1950년 하한·키보드·바깥 클릭을 지원하며 자료 영상에는 방송일을 숨긴다', async ({
  page,
}) => {
  await page.goto('/review?view=upload');
  const input = page.locator('#filmed-date');
  await expect(input).toHaveAttribute('max', '2026-09-22');
  await input.focus();
  await input.press('ArrowDown');
  const calendar = page.getByRole('dialog', { name: '촬영일 선택', exact: true });
  await expect(calendar).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(calendar).not.toBeVisible();
  await expect(input).toBeFocused();
  await input.press('ArrowDown');
  await page.locator('#registration-title').click();
  await expect(calendar).not.toBeVisible();
  await input.focus();
  await input.press('ArrowDown');
  await page.locator('#registration-title').focus();
  await expect(calendar).not.toBeVisible();
  await page.getByRole('button', { name: '촬영일 달력 열기', exact: true }).click();
  await calendar.getByRole('button', { name: /월 선택/ }).click();
  await calendar.getByRole('button', { name: /연도 선택/ }).click();
  for (let i = 0; i < 7; i++)
    await calendar.getByRole('button', { name: '촬영일 이전 연도 범위', exact: true }).click();
  await expect(
    calendar.getByRole('button', { name: '촬영일 이전 연도 범위', exact: true }),
  ).toBeDisabled();
  await calendar.getByRole('button', { name: '1950년', exact: true }).click();
  await calendar.getByRole('button', { name: '1월', exact: true }).click();
  await expect(
    calendar.getByRole('button', { name: '촬영일 이전 달', exact: true }),
  ).toBeDisabled();
  await page.keyboard.press('ArrowLeft');
  await expect(calendar.locator('[data-date="1950-01-01"]')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(input).toHaveValue('1950-01-01');
  await page.getByRole('radio', { name: '자료 영상', exact: true }).check();
  await expect(page.getByRole('button', { name: '방송일 달력 열기', exact: true })).toHaveCount(0);
  await expect(input).toHaveValue('1950-01-01');
});

test('등록 날짜 직접 입력 오류는 제출을 막으며 달력을 열어 수정할 수 있다', async ({ page }) => {
  let uploads = 0;
  await page.route('**/api/v1/clips', (route) => {
    uploads++;
    return route.fulfill({ status: 500 });
  });
  await page.goto('/review?view=upload');
  await page.locator('#video-file').setInputFiles({
    name: '영상.mp4',
    mimeType: 'video/mp4',
    buffer: readFileSync('e2e/preview-fixture.mp4'),
  });
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.locator('#broadcast-date').fill('2026-02-30');
  await page.locator('#filmed-date').fill('2026-09');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page.locator('#broadcast-date-error')).toHaveText(
    '방송일을 YYYY-MM-DD 형식의 올바른 날짜로 입력해 주세요.',
  );
  await expect(page.locator('#filmed-date-error')).toHaveText(
    '촬영일을 YYYY-MM-DD 형식의 올바른 날짜로 입력해 주세요.',
  );
  expect(uploads).toBe(0);
  for (const label of ['방송일', '촬영일']) {
    await page.getByRole('button', { name: `${label} 달력 열기`, exact: true }).click();
    const calendar = page.getByRole('dialog', { name: `${label} 선택`, exact: true });
    await calendar.locator('[data-date="2026-09-22"]').click();
  }
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect.poll(() => uploads).toBe(1);
});
