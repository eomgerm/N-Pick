import { chooseDateBasis, openDatePicker, periodTrigger } from './date-picker-helpers';
import { expect, test } from '@playwright/test';

test.beforeEach(async ({ context, page }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
  await page.clock.setFixedTime(new Date('2026-09-22T03:00:00Z'));
});

for (const width of [1440, 390]) {
  for (const path of ['/search', '/search/results?q=날짜']) {
    test(`${width}px ${path}에서 기간 작업을 마치면 사이드바도 접힌다`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.route('**/api/v1/search', (route) =>
        route.fulfill({
          status: 500,
          json: { isSuccess: false, code: 'COMM_500', message: '검색에 실패했습니다.' },
        }),
      );
      await page.goto(path);
      if (path.includes('/results')) {
        await expect(
          page.getByRole('heading', { name: '검색 결과를 불러오지 못했어요' }),
        ).toBeVisible();
      }
      const dock = page.getByRole('complementary', { name: '검색 도구', exact: true });
      const collapsedWidth = (await dock.boundingBox())!.width;
      for (const label of ['방송일', '촬영일']) {
        const trigger = periodTrigger(page);
        const dialog = page.getByRole('dialog', { name: '기간 설정', exact: true });
        const actions = ['적용', '닫기', 'Escape'];
        if (width > 760) actions.push('다시 클릭');
        for (const action of actions) {
          await trigger.click();
          await chooseDateBasis(page, label);
          await expect(dock).toHaveAttribute('data-expanded', 'true');
          await dialog.getByRole('button', { name: '최근 1년', exact: true }).click();
          await expect(dock).toHaveAttribute('data-expanded', 'true');
          if (action === 'Escape') await page.keyboard.press('Escape');
          else if (action === '다시 클릭') await trigger.click();
          else {
            await dialog
              .getByRole('button', {
                name: action === '닫기' ? '기간 설정 닫기' : action,
                exact: true,
              })
              .click();
          }
          await expect(dialog).not.toBeVisible();
          // 결과 화면의 적용은 URL 이동 중 버튼을 잠가 포커스가 해제됩니다.
          if (path === '/search' || action !== '적용') {
            await expect(trigger).toBeFocused();
          }
          await expect(dock).toHaveAttribute('data-expanded', 'false');
          await expect(dock).toHaveCSS('width', `${collapsedWidth}px`);
          await expect(trigger).toBeEnabled();
        }
      }
      await page.screenshot({ path: test.info().outputPath('completed-navigation.png') });
    });
  }
}

test('기준 전환과 기록 열기는 유지하고 바깥 클릭·키보드 이탈은 접는다', async ({ page }) => {
  await page.goto('/search');
  const dock = page.getByRole('complementary', { name: '검색 도구', exact: true });
  const trigger = periodTrigger(page);
  const dialog = await openDatePicker(page, '방송일');
  await chooseDateBasis(page, '촬영일');
  await expect(dialog).toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'true');
  const historyButton = dock.getByRole('button', { name: '이전 검색 기록', exact: true });
  await historyButton.click();
  await expect(dialog).not.toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'true');
  const history = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  await expect(history).toBeVisible();
  await trigger.click();
  await expect(history).not.toBeVisible();
  await expect(dialog).toBeVisible();
  await page.getByRole('button', { name: 'e2e-editor', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'false');
  await trigger.click();
  await dialog.getByRole('button', { name: '적용', exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(historyButton).toBeFocused();
  await expect(dialog).toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'true');
  await page.keyboard.press('Enter');
  await expect(history).toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'true');
  await trigger.click();
  await page.getByRole('searchbox').focus();
  await expect(dialog).not.toBeVisible();
  await expect(dock).toHaveAttribute('data-expanded', 'false');
});

test('기간 설정에서 다른 메뉴 오른쪽 끝을 눌러도 펼친 너비를 유지하며 전환한다', async ({
  page,
}) => {
  await page.goto('/search');
  const dock = page.getByRole('complementary', { name: '검색 도구', exact: true });
  for (const name of ['이전 검색 기록', '문의 사항']) {
    const dialog = await openDatePicker(page);
    await expect(dock).toHaveCSS('width', '224px');
    const button = dock.getByRole('button', { name, exact: true });
    const box = (await button.boundingBox())!;
    await page.mouse.move(box.x + box.width - 4, box.y + box.height / 2);
    await page.mouse.down();
    await expect(dock).toHaveAttribute('data-expanded', 'true');
    await expect(dialog).toBeVisible();
    await page.mouse.up();
    await expect(dialog).not.toBeVisible();
    await expect(button).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('complementary', { name, exact: true })).toBeVisible();
    await expect(dock).toHaveCSS('width', '224px');
  }
});

test('달력 포커스가 해제된 뒤 Escape로 닫아도 팝업만 남지 않는다', async ({ page }) => {
  await page.goto('/search');
  const dialog = await openDatePicker(page);
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('complementary', { name: '검색 도구', exact: true })).toHaveAttribute(
    'data-expanded',
    'false',
  );
  await expect(dialog).not.toBeVisible();
  await expect(periodTrigger(page)).toHaveAttribute('aria-expanded', 'false');
});
