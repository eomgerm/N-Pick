import { readFileSync } from 'node:fs';

import { expect, test, type Locator } from '@playwright/test';

import { searchFixture } from './search-fixture';

const sceneTitle =
  '설 연휴 첫날 이른 아침부터 서울역 대합실과 승강장을 가득 메운 귀성객들이 열차를 기다리며 이동하는 장면';
const clipTitle = 'KBC_20260214_뉴스9_설연휴_서울역_귀성길_현장_전체본.mp4';
const sceneThumbnail = readFileSync('public/images/news-scenes-triptych.png');

function isFullyVisible(locator: Locator) {
  return locator.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const content = range.getBoundingClientRect();
    const bounds = element.getBoundingClientRect();
    return content.height <= bounds.height + 0.5 && content.width <= bounds.width + 0.5;
  });
}

for (const viewport of [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
]) {
  test(`검색 상세가 제목과 신고 액션을 명확히 표시한다 (${viewport.name})`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
    await page.route('**/api/v1/search', (route) =>
      route.fulfill({
        status: 200,
        json: {
          isSuccess: true,
          code: 'COMM_200',
          message: '성공',
          data: {
            ...searchFixture,
            results: [
              {
                ...searchFixture.results[0],
                display_name: clipTitle,
                scene_description: sceneTitle,
                start_time_ms: 1_250,
                end_time_ms: 2_500,
              },
            ],
          },
        },
      }),
    );
    await page.route('**/api/v1/scenes/31/thumbnail', (route) =>
      route.fulfill({ contentType: 'image/png', body: sceneThumbnail }),
    );

    await page.goto('/search/results?q=서울역%20귀성객');
    const reportButton = page.getByRole('button', { name: `${sceneTitle} 이상 신고하기` });
    await expect(reportButton).toBeVisible();
    await expect(reportButton.locator('svg')).toHaveClass(/lucide-triangle-alert/);

    if (viewport.name === 'desktop') {
      await page.screenshot({ path: testInfo.outputPath('search-results-desktop.png') });
    }

    await page.getByRole('button', { name: `1위 ${sceneTitle} Preview 열기` }).click();
    const dialog = page.getByRole('dialog');
    const title = dialog.getByRole('heading', { level: 2, name: sceneTitle });
    const clip = dialog.getByText(clipTitle, { exact: true });
    const evidence = dialog.getByRole('heading', { name: '검색 근거' });
    const sceneInfo = dialog.getByRole('heading', { name: '장면 정보' });

    await expect(title).toBeVisible();
    await expect(clip).toBeVisible();
    await expect(clip).not.toHaveAttribute('title');
    expect(await isFullyVisible(title)).toBe(true);
    expect(await isFullyVisible(clip)).toBe(true);
    expect((await evidence.boundingBox())!.y).toBeLessThan((await sceneInfo.boundingBox())!.y);
    const dialogReportButton = dialog.getByRole('button', { name: '이상해요' });
    await expect(dialogReportButton).toBeInViewport({ ratio: 1 });
    await expect(dialogReportButton.locator('svg')).toHaveClass(/lucide-triangle-alert/);
    const reportBounds = (await dialogReportButton.boundingBox())!;
    const closeBounds = (await dialog.getByRole('button', { name: 'Preview 닫기' }).boundingBox())!;
    const sceneMetaBounds = (await dialog
      .getByText('00:01 – 00:02', { exact: true })
      .locator('..')
      .boundingBox())!;
    const playerBounds = (await dialog.locator('video').boundingBox())!;
    expect(reportBounds.y + reportBounds.height).toBeLessThan(playerBounds.y);
    expect(
      Math.abs(reportBounds.x + reportBounds.width - (closeBounds.x + closeBounds.width)),
    ).toBeLessThanOrEqual(1);
    expect(
      Math.abs(
        reportBounds.y + reportBounds.height / 2 - (sceneMetaBounds.y + sceneMetaBounds.height / 2),
      ),
    ).toBeLessThanOrEqual(1);
    await page.screenshot({
      path: testInfo.outputPath(`preview-${viewport.name}-meta-row-aligned.png`),
    });
  });
}
