import { openDatePicker } from './date-picker-helpers';
import { expect, test } from '@playwright/test';

test.use({ timezoneId: 'Pacific/Kiritimati' });

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
  // 기기는 9월 23일이지만 한국은 아직 9월 22일입니다.
  await page.clock.setFixedTime(new Date('2026-09-22T14:30:00Z'));
});

test('두 검색 달력은 한국의 오늘까지만 클릭·키보드·월·연도 선택을 허용한다', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/search');
  for (const label of ['방송일', '촬영일']) {
    await openDatePicker(page, label);
    const dialog = page.getByRole('dialog', { name: '기간 설정' });
    for (const endpoint of ['from', 'to']) {
      const calendar = dialog.locator(`[data-endpoint="${endpoint}"]`);
      const today = calendar.locator('[data-date="2026-09-22"]');
      await expect(today).toBeEnabled();
      await expect(calendar.locator('[data-date="2026-09-23"]')).toBeDisabled();
      await expect(calendar.getByRole('button', { name: /다음 달/ })).toBeDisabled();
      await today.focus();
      for (const key of ['ArrowRight', 'ArrowDown', 'End']) {
        await today.press(key);
        await expect(today).toBeFocused();
      }
      await calendar.getByRole('button', { name: /월 선택/ }).click();
      await expect(calendar.getByRole('button', { name: '10월', exact: true })).toBeDisabled();
      await calendar.getByRole('button', { name: /연도 선택/ }).click();
      await expect(calendar.getByRole('button', { name: '2027년', exact: true })).toBeDisabled();
      await calendar.getByRole('button', { name: '2026년', exact: true }).click();
      await calendar.getByRole('button', { name: '9월', exact: true }).click();
      await today.click();
    }
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(
      page.getByRole('button', { name: new RegExp(`${label} 2026.09.22 – 2026.09.22`) }),
    ).toBeVisible();
  }
  expect(errors).toEqual([]);
});

for (const [label, prefix] of [
  ['방송일', 'broadcast'],
  ['촬영일', 'filming'],
]) {
  test(`${label}에 직접 입력한 미래 URL은 검색하지 않고 달력에서 수정할 수 있다`, async ({
    page,
  }) => {
    let searches = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/api/v1/search')) searches++;
    });
    await page.goto(`/search/results?q=뉴스&${prefix}From=2026-09-23&${prefix}To=2026-09-24`);
    const message = '시작일과 종료일은 오늘 이후 날짜로 선택할 수 없습니다.';
    await expect(page.getByText(message).first()).toBeVisible();
    expect(searches).toBe(0);
    await openDatePicker(page, label);
    const dialog = page.getByRole('dialog', { name: '기간 설정' });
    await expect(dialog.locator('[data-endpoint="from"] [data-date="2026-09-22"]')).toBeFocused();
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(dialog.getByRole('alert')).toHaveText(message);
    expect(searches).toBe(0);
    await dialog.getByRole('button', { name: '최근 1년' }).click();
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(page).toHaveURL((url) => url.searchParams.get(`${prefix}To`) === '2026-09-22');
    await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
    expect(searches).toBe(1);
  });
}
