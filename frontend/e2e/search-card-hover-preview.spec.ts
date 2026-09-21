import { expect, test, type Locator, type Page } from '@playwright/test';

const sceneStart = 1.25;
const sceneEnd = 2.5;

test.beforeEach(async ({ context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
});

async function openResults(page: Page) {
  await page.goto('/search/results?q=장면');
  const card = page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' });
  await expect(card).toBeVisible();
  return card;
}

function readTime(video: Locator) {
  return video.evaluate((element: HTMLVideoElement) => element.currentTime);
}

test('카드에 마우스를 올리면 장면 구간 영상을 재생하고 떼면 중단한다', async ({ page }) => {
  const mediaRequests: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/media/21')) mediaRequests.push(request.url());
  });

  const card = await openResults(page);
  const video = page.locator('article video');
  await expect(video).toHaveCount(0);
  expect(mediaRequests).toHaveLength(0);

  await card.hover();
  await expect(video).toHaveCount(1);
  await expect.poll(() => readTime(video)).toBeGreaterThanOrEqual(sceneStart);

  const samples: number[] = [];
  for (let index = 0; index < 12; index += 1) {
    samples.push(await readTime(video));
    await page.waitForTimeout(250);
  }
  // The clip is 8s long, so staying inside the window across 3s proves the loop.
  // timeupdate fires about every 250ms, so allow one tick of overshoot past the end.
  expect(Math.min(...samples)).toBeGreaterThanOrEqual(sceneStart - 0.05);
  expect(Math.max(...samples)).toBeLessThanOrEqual(sceneEnd + 0.35);
  expect(await video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(false);
  expect(await video.evaluate((element: HTMLVideoElement) => element.muted)).toBe(true);

  await page.mouse.move(0, 0);
  await expect(video).toHaveCount(0);
  const requestsAfterLeave = mediaRequests.length;
  await page.waitForTimeout(750);
  expect(mediaRequests).toHaveLength(requestsAfterLeave);
});

test('키보드 focus에서도 장면 구간 영상을 재생하고 focus를 잃으면 중단한다', async ({ page }) => {
  const card = await openResults(page);
  const video = page.locator('article video');

  await card.focus();
  await expect(video).toHaveCount(1);
  await expect.poll(() => readTime(video)).toBeGreaterThanOrEqual(sceneStart);

  await card.blur();
  await expect(video).toHaveCount(0);
});

test('prefers-reduced-motion에서는 장면 시작 화면만 보여 주고 재생하지 않는다', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const card = await openResults(page);
  const video = page.locator('article video');

  await card.hover();
  await expect(video).toHaveCount(1);
  await expect.poll(() => readTime(video)).toBeGreaterThanOrEqual(sceneStart);
  await page.waitForTimeout(750);
  expect(await video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(true);
  expect(await readTime(video)).toBeLessThan(sceneEnd);
});
