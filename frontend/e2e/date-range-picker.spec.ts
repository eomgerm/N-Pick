import { chooseDateBasis, periodTrigger } from './date-picker-helpers';
import { expect, test } from '@playwright/test';

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
  await page.clock.setFixedTime(new Date('2026-09-22T03:00:00Z'));
  await page.goto('/search');
});

for (const [label, prefix] of [
  ['방송일', 'broadcast'],
  ['촬영일', 'filming'],
]) {
  test(`${label} 양쪽 달력의 역순 선택을 정렬하고 적용·취소·검색에 반영한다`, async ({ page }) => {
    const trigger = periodTrigger(page);
    await trigger.click();
    await chooseDateBasis(page, label);
    const dialog = page.getByRole('dialog', { name: '기간 설정' });
    const from = dialog.locator('[data-endpoint="from"]');
    const to = dialog.locator('[data-endpoint="to"]');

    await from.locator('[data-date="2026-09-01"]').click();
    await to.getByRole('button', { name: '종료일 이전 달' }).click();
    await to.locator('[data-date="2026-08-01"]').click();
    await expect(
      from.getByRole('button', { name: '2026년 8월 1일, 시작일', exact: true }),
    ).toHaveAttribute('aria-pressed', 'true');
    await expect(
      to.getByRole('button', { name: '2026년 9월 1일, 종료일', exact: true }),
    ).toHaveAttribute('aria-pressed', 'true');
    await dialog.getByRole('button', { name: '적용', exact: true }).click();
    await expect(trigger).toHaveAccessibleName(`기간 설정: ${label} 2026.08.01 – 2026.09.01`);

    await trigger.click();
    await chooseDateBasis(page, label);
    await from.getByRole('button', { name: '시작일 다음 달' }).click();
    await from.locator('[data-date="2026-09-15"]').click();
    await expect(from.locator('[data-date="2026-09-01"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(to.locator('[data-date="2026-09-15"]')).toHaveAttribute('aria-pressed', 'true');
    await dialog.screenshot({ path: test.info().outputPath('connected-range-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await dialog.screenshot({ path: test.info().outputPath('connected-range-mobile.png') });
    await page.setViewportSize({ width: 1280, height: 720 });
    await dialog.getByRole('button', { name: '취소', exact: true }).click();
    await expect(trigger).toHaveAccessibleName(`기간 설정: ${label} 2026.08.01 – 2026.09.01`);

    const search = page.getByRole('search', { name: '뉴스 장면 검색' });
    await search.getByRole('searchbox', { name: '뉴스 장면 검색어' }).fill('날짜 선택 확인');
    await search.getByRole('button', { name: '장면 찾기' }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get(`${prefix}From`) === '2026-08-01' &&
        url.searchParams.get(`${prefix}To`) === '2026-09-01',
    );
  });
}

test('한쪽 날짜는 적용을 막고 같은 날은 하루 기간으로 적용한다', async ({ page }) => {
  const trigger = page.getByRole('button', { name: /^기간 설정:/ });
  await trigger.click();
  await chooseDateBasis(page, '방송일');
  const dialog = page.getByRole('dialog', { name: '기간 설정' });
  await dialog.locator('[data-endpoint="to"] [data-date="2026-09-08"]').click();
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('시작일과 종료일을 모두 선택해 주세요.');
  await dialog.locator('[data-endpoint="from"] [data-date="2026-09-08"]').click();
  await dialog.screenshot({ path: test.info().outputPath('single-day-range.png') });
  await dialog.getByRole('button', { name: '적용', exact: true }).click();
  await expect(trigger).toHaveAccessibleName('기간 설정: 방송일 2026.09.08 – 2026.09.08');
});
