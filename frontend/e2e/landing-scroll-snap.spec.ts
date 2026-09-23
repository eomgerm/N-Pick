import { expect, test, type Page } from '@playwright/test';

const scrollPosition = (page: Page) => page.evaluate(() => window.scrollY);
const rolePosition = (page: Page) =>
  page
    .locator('[aria-labelledby="landing-title"]')
    .evaluate((hero) => (hero as HTMLElement).offsetHeight);

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/landing');
  await expect(page.locator('[data-ready]')).toHaveAttribute('data-ready', 'true');
});

test('작은 움직임은 유지하고 같은 방향 누적 임계값을 넘으면 양방향으로 자동 이동한다', async ({
  page,
}) => {
  await page.mouse.move(1100, 500);
  await page.mouse.wheel(0, 40);
  await expect.poll(() => scrollPosition(page)).toBe(40);
  await page.mouse.wheel(0, 40);
  await expect.poll(() => scrollPosition(page)).toBe(80);
  await page.waitForTimeout(200);
  expect(await scrollPosition(page)).toBe(80);
  await page.mouse.wheel(0, 20);
  const rolesTop = await rolePosition(page);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop);
  await page.mouse.wheel(0, -40);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop - 40);
  await page.waitForTimeout(200);
  expect(await scrollPosition(page)).toBe(rolesTop - 40);
  await page.mouse.wheel(0, -60);
  await expect.poll(() => scrollPosition(page)).toBe(0);
});

test('방향을 바꾸면 이전 방향의 누적량을 버린다', async ({ page }) => {
  await page.mouse.move(1100, 500);
  await page.mouse.wheel(0, 70);
  await expect.poll(() => scrollPosition(page)).toBe(70);
  await page.mouse.wheel(0, -40);
  await expect.poll(() => scrollPosition(page)).toBe(30);
  await page.mouse.wheel(0, 60);
  await expect.poll(() => scrollPosition(page)).toBe(90);
  await page.waitForTimeout(200);
  expect(await scrollPosition(page)).toBe(90);
  await page.mouse.wheel(0, 40);
  await expect.poll(() => scrollPosition(page)).toBe(await rolePosition(page));
});

test('자동 이동과 연속 휠 입력이 반대 방향 이동을 반복하지 않는다', async ({ page }) => {
  await page.mouse.move(1100, 500);
  for (let index = 0; index < 8; index++) {
    await page.mouse.wheel(0, 40);
    await page.waitForTimeout(30);
  }
  const rolesTop = await rolePosition(page);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop);
  await page.waitForTimeout(250);
  expect(await scrollPosition(page)).toBe(rolesTop);
  for (let index = 0; index < 8; index++) {
    await page.mouse.wheel(0, -40);
    await page.waitForTimeout(30);
  }
  await expect.poll(() => scrollPosition(page)).toBe(0);
});

test('모바일 역할 영역 안에서는 자유롭게 읽고 상단 경계를 넘어 올라오면 히어로로 복귀한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.mouse.move(200, 400);
  await page.mouse.wheel(0, 100);
  const rolesTop = await rolePosition(page);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop);
  await page.mouse.wheel(0, 100);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop + 100);
  await page.mouse.wheel(0, -50);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop + 50);
  await page.waitForTimeout(200);
  expect(await scrollPosition(page)).toBe(rolesTop + 50);
  await page.mouse.wheel(0, -100);
  await expect.poll(() => scrollPosition(page)).toBe(rolesTop - 50);
  await page.mouse.wheel(0, -50);
  await expect.poll(() => scrollPosition(page)).toBe(0);
});

test('Scroll down 버튼과 키보드 스크롤도 같은 목적지에 도착한다', async ({ page }) => {
  await page.getByRole('button', { name: 'Scroll down' }).click();
  await expect.poll(() => scrollPosition(page)).toBe(await rolePosition(page));
  await page.keyboard.press('Home');
  await expect.poll(() => scrollPosition(page)).toBe(0);
  await page.keyboard.press('PageDown');
  await expect.poll(() => scrollPosition(page)).toBe(await rolePosition(page));
});

test('모션 감소에서는 임계값 이동에 애니메이션을 사용하지 않는다', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.mouse.move(1100, 500);
  await page.mouse.wheel(0, 100);
  await expect.poll(() => scrollPosition(page)).toBe(await rolePosition(page));
  await page.mouse.wheel(0, -100);
  await expect.poll(() => scrollPosition(page)).toBe(0);
});
