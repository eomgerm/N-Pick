import { expect, test, type Page } from '@playwright/test';

async function openPreview(page: Page, startTimeMs = 1250, endTimeMs = 2500, clipId = '21') {
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' }]);
  await page.route('**/api/v1/review/inquiries/41', (route) =>
    route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: {
          feedbackId: '41',
          status: 'OPEN',
          resolution: null,
          createdAt: '2026-09-11T01:00:00Z',
          sceneId: '31',
          scene: {
            sceneId: '31',
            clipId,
            clipTitle: '테스트 원본',
            startTimeMs,
            endTimeMs,
            pipelineRunId: '11',
            processingNo: 1,
          },
          comment: '선택 장면 확인',
          resultRank: 1,
          execution: { queryText: '테스트' },
          evidence: [],
          history: {},
        },
      },
    }),
  );
  await page.goto('/review?inquiry=41');
  await page.getByRole('button', { name: '문의 장면 재생' }).click();
  return page.getByRole('dialog');
}

test('실제 MP4는 장면 시작에서 재생하고 경계 이후 계속 재생하며 Range와 세션을 사용한다', async ({
  page,
}) => {
  const mediaResponse = page.waitForResponse(
    (response) => response.url().endsWith('/media/21') && response.status() === 206,
  );
  const dialog = await openPreview(page);
  const response = await mediaResponse;
  expect(response.headers()['content-range']).toMatch(/^bytes 0-/);
  expect(response.request().headers()['range']).toMatch(/^bytes=/);
  const video = dialog.locator('video');
  await expect(dialog.getByRole('button', { name: '구간 반복', exact: true })).toHaveAttribute(
    'aria-pressed',
    'false',
  );
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeGreaterThanOrEqual(1.25);
  await expect(dialog.getByText('IN 00:01 · OUT 00:02')).toBeVisible();
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeGreaterThan(2.5);
  await video.evaluate((element: HTMLVideoElement) => {
    element.pause();
    element.currentTime = 6;
  });
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeCloseTo(6, 1);
  await dialog.getByRole('button', { name: '구간 다시 재생' }).click();
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeLessThan(2.5);
  await expect(dialog.getByText('송출 전 최종 확인')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('preview-player.png') });
  const previousVideo = await video.elementHandle();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: '문의 장면 재생' })).toBeFocused();
  await expect(page.locator('video')).toHaveCount(0);
  expect(
    await previousVideo!.evaluate(
      (element: HTMLVideoElement) => element.paused && !element.hasAttribute('src'),
    ),
  ).toBe(true);
  await page.getByRole('button', { name: '문의 장면 재생' }).click();
  await expect(dialog.getByRole('button', { name: '구간 다시 재생' })).toBeEnabled();
});

test('0초 시작은 seeked 없이도 준비되고 자동재생 차단 후 수동 재생할 수 있다', async ({ page }) => {
  await page.addInitScript(() => {
    const play = HTMLMediaElement.prototype.play;
    let blocked = false;
    HTMLMediaElement.prototype.play = function () {
      if (!blocked) {
        blocked = true;
        return Promise.reject(new DOMException('blocked', 'NotAllowedError'));
      }
      return play.call(this);
    };
  });
  const dialog = await openPreview(page, 0, 1500);
  await expect(
    dialog.getByText('자동으로 재생하지 못했습니다. 영상의 재생 버튼을 눌러 주세요.'),
  ).toBeVisible();
  await dialog.locator('video').evaluate((element: HTMLVideoElement) => element.play());
  await expect(dialog.getByText('재생 중', { exact: true })).toBeVisible();
});

test('재생 실패는 한국어로 안내하고 재시도할 수 있다', async ({ page }) => {
  await page.route('**/api/v1/media/21', (route) => route.fulfill({ status: 404, body: '' }));
  const dialog = await openPreview(page);
  await expect(dialog.getByRole('alert')).toContainText('영상을 불러오거나 재생할 수 없습니다');
  await page.unroute('**/api/v1/media/21');
  await dialog.getByRole('button', { name: '다시 시도' }).click();
  await expect(dialog.getByRole('button', { name: '구간 다시 재생' })).toBeEnabled();
});

test('원본보다 긴 장면은 0초로 대체하지 않는다', async ({ page }) => {
  const dialog = await openPreview(page, 1000, 100000);
  await expect(dialog.getByRole('alert')).toContainText('장면 구간이 원본 영상 길이와 맞지 않아');
  await expect(dialog.getByRole('button', { name: '구간 다시 재생' })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '구간 반복', exact: true })).toBeDisabled();
});

test('구간 반복은 두 번 이상 반복하고 끄면 원본을 다시 불러오지 않고 경계 이후 재생한다', async ({
  page,
}) => {
  const dialog = await openPreview(page);
  const video = dialog.locator('video');
  const loop = dialog.getByRole('button', { name: '구간 반복', exact: true });
  await expect(loop).toBeEnabled();
  await video.evaluate((element: HTMLVideoElement) => {
    element.dataset.loopStarts = '0';
    element.dataset.reloads = '0';
    element.addEventListener('seeked', () => {
      if (element.currentTime >= 1.25 && element.currentTime < 1.5) {
        element.dataset.loopStarts = String(Number(element.dataset.loopStarts) + 1);
      }
    });
    element.addEventListener('emptied', () => {
      element.dataset.reloads = String(Number(element.dataset.reloads) + 1);
    });
  });
  await loop.focus();
  await page.keyboard.press('Space');
  await expect(loop).toHaveAttribute('aria-pressed', 'true');
  await video.evaluate((element: HTMLVideoElement) => {
    element.playbackRate = 2;
    element.dataset.maxLoopTime = '0';
    const measure = () => {
      if (!element.isConnected || element.dataset.stopMeasuring) return;
      if (!element.seeking) {
        element.dataset.maxLoopTime = String(
          Math.max(Number(element.dataset.maxLoopTime), element.currentTime),
        );
      }
      requestAnimationFrame(measure);
    };
    requestAnimationFrame(measure);
  });
  await expect
    .poll(() => video.getAttribute('data-loop-starts'), { timeout: 8_000 })
    .toMatch(/^[2-9]\d*$/);
  const maxLoopTime = await video.evaluate((element: HTMLVideoElement) => {
    element.dataset.stopMeasuring = 'true';
    element.playbackRate = 1;
    return Number(element.dataset.maxLoopTime);
  });
  expect(maxLoopTime).toBeLessThan(2.7);
  await page.screenshot({ path: test.info().outputPath('preview-loop.png') });
  await loop.click();
  await expect(loop).toHaveAttribute('aria-pressed', 'false');
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeGreaterThan(2.6);
  await expect(video).toHaveAttribute('data-reloads', '0');
  await loop.click();
  const previousVideo = await video.elementHandle();
  await page.keyboard.press('Escape');
  expect(
    await previousVideo!.evaluate(
      (element: HTMLVideoElement) => element.paused && !element.hasAttribute('src'),
    ),
  ).toBe(true);
  await page.getByRole('button', { name: '문의 장면 재생' }).click();
  await expect(loop).toHaveAttribute('aria-pressed', 'false');
});

test('일시 정지 중 반복을 켜도 재생하지 않으며 재생과 구간 밖 탐색 시 선택 구간으로 돌아온다', async ({
  page,
}) => {
  const dialog = await openPreview(page);
  const video = dialog.locator('video');
  const loop = dialog.getByRole('button', { name: '구간 반복', exact: true });
  await expect(loop).toBeEnabled();
  await video.evaluate((element: HTMLVideoElement) => {
    element.pause();
    element.currentTime = 6;
  });
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => !element.seeking))
    .toBe(true);
  await loop.click();
  await expect(dialog.getByText('일시 정지', { exact: true })).toBeVisible();
  expect(
    await video.evaluate(
      (element: HTMLVideoElement) => element.paused && element.currentTime === 6,
    ),
  ).toBe(true);
  await video.evaluate((element: HTMLVideoElement) => {
    element.currentTime = element.duration;
  });
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => !element.seeking))
    .toBe(true);
  await expect(dialog.getByText('일시 정지', { exact: true })).toBeVisible();
  expect(await video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(true);
  await video.evaluate((element: HTMLVideoElement) => element.play());
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeLessThan(2.5);
  await video.evaluate((element: HTMLVideoElement) => {
    element.currentTime = 0;
  });
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeGreaterThanOrEqual(1.25);
});

test('원본 영상 끝과 OUT이 같아도 종료 후 선택 구간이 반복된다', async ({ page }) => {
  const initialDialog = await openPreview(page);
  await expect(initialDialog.getByRole('button', { name: '구간 다시 재생' })).toBeEnabled();
  const duration = await initialDialog
    .locator('video')
    .evaluate((element: HTMLVideoElement) => element.duration);
  // Canvas recording duration varies slightly from the nominal eight seconds.
  const endTimeMs = Math.ceil(duration * 1000);
  const startTimeMs = endTimeMs - 1000;
  const dialog = await openPreview(page, startTimeMs, endTimeMs);
  const video = dialog.locator('video');
  const loop = dialog.getByRole('button', { name: '구간 반복', exact: true });
  await expect(loop).toBeEnabled();
  await video.evaluate((element: HTMLVideoElement, start) => {
    element.dataset.loopStarts = '0';
    element.addEventListener('seeked', () => {
      if (element.currentTime >= start && element.currentTime < start + 0.25) {
        element.dataset.loopStarts = String(Number(element.dataset.loopStarts) + 1);
      }
    });
  }, startTimeMs / 1000);
  await loop.click();
  await expect
    .poll(() => video.getAttribute('data-loop-starts'), { timeout: 8_000 })
    .toMatch(/^[2-9]\d*$/);
  expect(await video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(false);
});

test('끝나지 않는 seek는 무한 대기하지 않고 이동 실패로 안내한다', async ({ page }) => {
  const dialog = await openPreview(page);
  await expect(dialog.getByRole('button', { name: '구간 다시 재생' })).toBeEnabled();
  await dialog.locator('video').evaluate((element: HTMLVideoElement) => element.pause());
  await page.clock.install();
  await dialog
    .locator('video')
    .evaluate((element: HTMLVideoElement) => element.dispatchEvent(new Event('seeking')));
  await page.clock.fastForward(20_001);
  await expect(dialog.getByRole('alert')).toContainText('영상 위치로 이동하지 못했습니다');
});
