import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

import { openRegistration, openRegistrationWithVideo } from './registration-helpers';

const videoBytes = readFileSync('e2e/preview-fixture.mp4');

test.beforeEach(async ({ context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
  ]);
});

test('위장 파일을 차단하고 선택 영역 안에서 영상 정보·재선택·삭제를 제공한다', async ({ page }) => {
  let uploads = 0;
  await page.route('**/api/v1/clips', (route) => {
    uploads++;
    return route.fulfill({ status: 500 });
  });
  await openRegistration(page);
  const videoInput = page.locator('#video-file');
  const videoZone = page.locator('[data-kind="video"]');
  const isInert = (selector: string) =>
    page.locator(selector).evaluate((element) => Boolean(element.closest('[inert]')));
  await videoZone.screenshot({ path: 'test-results/video-registration-empty.png' });
  await videoInput.setInputFiles({
    name: '가짜.mp4',
    mimeType: 'video/mp4',
    buffer: Buffer.from('텍스트'),
  });
  await expect(page.locator('#video-error')).toContainText(
    '파일 내용이 MP4 또는 MOV 영상 형식이 아닙니다',
  );
  await expect(videoZone).not.toContainText('선택됨');
  // 오류 문구는 드롭존 옆이 아니라 아래에 놓인다.
  const zoneBox = await videoZone.boundingBox();
  const errorBox = await page.locator('#video-error').boundingBox();
  expect(errorBox!.y).toBeGreaterThanOrEqual(zoneBox!.y + zoneBox!.height);
  await page.evaluate(() => window.scrollTo(0, 0));
  await videoZone.screenshot({ path: 'test-results/video-registration-error.png' });
  // 영상을 고르기 전에는 나머지 입력과 등록 버튼을 조작할 수 없다.
  expect(await isInert('#registration-title')).toBe(true);
  expect(await isInert('#rights-confirmed')).toBe(true);
  await expect(page.getByRole('button', { name: '등록', exact: true })).toBeDisabled();
  expect(uploads).toBe(0);

  await videoInput.setInputFiles({ name: '첫번째.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(videoZone).toContainText('첫번째.mp4');
  await expect(videoZone).toContainText('선택됨');
  await expect(videoZone).toContainText('파일 선택');
  await expect(page.getByRole('list', { name: '선택한 영상 파일' })).toHaveCount(0);
  expect(await isInert('#registration-title')).toBe(false);
  await page.locator('#registration-title').fill('유지할 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();

  const subtitle = page.locator('#subtitle-file');
  await subtitle.setInputFiles({
    name: '가짜.srt',
    mimeType: 'text/plain',
    buffer: Buffer.from('일반 텍스트'),
  });
  await expect(page.locator('#subtitle-error')).toContainText('올바른 자막 형식이 아닙니다');
  await subtitle.setInputFiles({
    name: '자막.vtt',
    mimeType: 'text/vtt',
    buffer: Buffer.from('WEBVTT\n\n00:00.000 --> 00:01.000\n뉴스'),
  });
  await expect(page.locator('#subtitle-selection')).toContainText('자막.vtt');

  const transfer = await page.evaluateHandle(() => {
    const data = new DataTransfer();
    data.items.add(new File(['%PDF-1.7\n문서'], '위장.txt', { type: 'text/plain' }));
    return data;
  });
  await page.locator('label[data-kind="script"]').dispatchEvent('drop', { dataTransfer: transfer });
  await transfer.dispose();
  await expect(page.locator('#script-error')).toContainText('UTF-8 TXT');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  expect(uploads).toBe(0);
  await page
    .locator('#script-file')
    .setInputFiles({ name: '대본.txt', mimeType: 'text/plain', buffer: Buffer.from('정상 대본') });
  await expect(page.locator('#script-selection')).toContainText('대본.txt');

  await videoInput.setInputFiles({
    name: '재선택한-영상.mp4',
    mimeType: 'video/mp4',
    buffer: videoBytes,
  });
  await expect(videoZone).toContainText('재선택한-영상.mp4');
  await expect(videoZone).not.toContainText('첫번째.mp4');
  await expect(page.locator('#registration-title')).toHaveValue('유지할 제목');
  await expect(page.locator('#subtitle-selection')).toContainText('자막.vtt');
  await expect(page.locator('#rights-confirmed')).toBeChecked();
  await page.evaluate(() => window.scrollTo(0, 0));
  await videoZone.screenshot({ path: 'test-results/video-registration-selected.png' });
  await page.setViewportSize({ width: 390, height: 844 });
  await videoInput.setInputFiles({
    name: `${'긴영상파일명'.repeat(15)}.mp4`,
    mimeType: 'video/mp4',
    buffer: videoBytes,
  });
  await expect(videoZone).toContainText('긴영상파일명');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.evaluate(() => window.scrollTo(0, 0));
  await videoZone.screenshot({ path: 'test-results/video-registration-mobile.png' });
  await page.getByRole('button', { name: '영상 파일 삭제', exact: true }).click();
  await expect(videoZone).toContainText('영상 파일 추가하기');
  // 영상을 지워 다시 잠겨도 입력한 값은 남는다.
  expect(await isInert('#registration-title')).toBe(true);
  await expect(page.locator('#registration-title')).toHaveValue('유지할 제목');
});

test('영상을 고르고 다시 골라도 키보드 포커스가 파일 선택에 남는다', async ({ page }) => {
  await openRegistration(page);
  const input = page.locator('#video-file');
  await input.focus();
  await input.setInputFiles({ name: '첫번째.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('첫번째.mp4');
  await expect(input).toBeFocused();
  await input.setInputFiles({ name: '두번째.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('두번째.mp4');
  await expect(input).toBeFocused();
});

test('영상을 고른 뒤에는 파일 선택 버튼으로만 파일 창이 열린다', async ({ page }) => {
  await openRegistration(page);
  await page
    .locator('#video-file')
    .setInputFiles({ name: '뉴스.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('뉴스.mp4');

  let choosers = 0;
  page.on('filechooser', () => choosers++);
  // 파일명 위를 실제 좌표로 누른다. 투명한 파일 input 이 덮고 있으면 여기서 파일 창이 열린다.
  const name = await page.locator('#video-selection strong').boundingBox();
  await page.mouse.click(name!.x + 10, name!.y + name!.height / 2);
  await page.waitForTimeout(500);
  expect(choosers).toBe(0);

  const chooser = page.waitForEvent('filechooser');
  await page.locator('[data-kind="video"]').getByText('파일 선택', { exact: true }).click();
  await chooser;
});

test('영상을 고르기 전에도 취소로 등록 화면을 떠날 수 있다', async ({ page }) => {
  await openRegistration(page);
  await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page).not.toHaveURL(/view=upload/);
});

test('선택한 영상을 등록 전에 브라우저에서 재생해 확인한다', async ({ page }) => {
  await openRegistration(page);
  const input = page.locator('#video-file');
  const preview = page.getByLabel('선택한 영상 미리보기');
  await expect(preview).toHaveCount(0);

  await input.setInputFiles({ name: '첫번째.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(preview).toHaveAttribute('src', /^blob:/);
  const firstSource = await preview.getAttribute('src');
  // 메타데이터를 읽으면 용량 뒤에 길이(mm:ss)가 붙는다.
  await expect(page.locator('#video-selection')).toContainText(/\d{2}:\d{2}/);
  await preview.evaluate((element: HTMLVideoElement) => element.play());
  await expect
    .poll(() => preview.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeGreaterThan(0);

  await input.setInputFiles({ name: '두번째.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('두번째.mp4');
  await expect(preview).toHaveAttribute('src', /^blob:/);
  expect(await preview.getAttribute('src')).not.toBe(firstSource);

  await page.getByRole('button', { name: '영상 파일 삭제', exact: true }).click();
  await expect(preview).toHaveCount(0);
});

test('재생할 수 없는 코덱이어도 안내만 하고 등록은 막지 않는다', async ({ page }) => {
  await openRegistration(page);
  const atom = (type: string) => {
    const bytes = Buffer.alloc(12);
    bytes.writeUInt32BE(12);
    bytes.write(type, 4);
    return bytes;
  };
  // 컨테이너 헤더 검사는 통과하지만 브라우저가 디코딩할 트랙이 없는 MOV.
  await page.locator('#video-file').setInputFiles({
    name: '디코딩불가.mov',
    mimeType: 'video/quicktime',
    buffer: Buffer.concat([atom('wide'), atom('mdat'), atom('moov')]),
  });
  await expect(page.locator('#video-selection')).toContainText('디코딩불가.mov');
  await expect(page.getByText('이 브라우저에서는 미리보기를 재생할 수 없어요')).toBeVisible();
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await expect(page.getByRole('button', { name: '등록', exact: true })).toBeEnabled();
});

test('자막이나 대본 하나만 골라도 두 추가 자료 칸 높이가 같다', async ({ page }) => {
  await openRegistrationWithVideo(page);
  const height = (kind: string) =>
    page.locator(`label[data-kind="${kind}"]`).evaluate((e) => e.getBoundingClientRect().height);

  await page.locator('#subtitle-file').setInputFiles({
    name: '자막.srt',
    mimeType: 'text/plain',
    buffer: Buffer.from('1\n00:00:00,000 --> 00:00:01,000\n뉴스\n'),
  });
  await expect(page.locator('#subtitle-selection')).toContainText('자막.srt');
  expect(await height('subtitle')).toBeCloseTo(await height('script'), 0);

  await page.getByRole('button', { name: '자막 파일 삭제' }).click();
  await page
    .locator('#script-file')
    .setInputFiles({ name: '대본.txt', mimeType: 'text/plain', buffer: Buffer.from('대본') });
  await expect(page.locator('#script-selection')).toContainText('대본.txt');
  expect(await height('script')).toBeCloseTo(await height('subtitle'), 0);
});

test('오른쪽에 오류가 생겨도 왼쪽 영상 영역은 움직이지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await openRegistrationWithVideo(page);
  const bottom = (selector: string) =>
    page.locator(selector).evaluate((e) => Math.round(e.getBoundingClientRect().bottom));
  const video = '[aria-labelledby="video-label"]';
  const details = '[aria-labelledby="supplement-label"]';

  // 오류가 없을 때는 두 열의 아래 끝이 맞는다.
  const before = await bottom(video);
  expect(Math.abs(before - (await bottom(details)))).toBeLessThanOrEqual(1);

  await page
    .locator('#subtitle-file')
    .setInputFiles({ name: '가짜.srt', mimeType: 'text/plain', buffer: Buffer.from('자막 아님') });
  await expect(page.locator('#subtitle-error')).toBeVisible();
  expect(await bottom(details)).toBeGreaterThan(before);
  expect(await bottom(video)).toBe(before);
});

test('영상을 고른 등록 화면은 1080p 한 화면에 들어온다', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await openRegistration(page);
  await page
    .locator('#video-file')
    .setInputFiles({ name: '뉴스.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('뉴스.mp4');
  await page.screenshot({ path: 'test-results/video-registration-1080p.png' });
  expect(
    await page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight),
  ).toBe(true);
});

test('느린 파일 검사가 제출을 막고 빠른 재선택 결과를 덮어쓰지 않는다', async ({ page }) => {
  await page.addInitScript(() => {
    const slice = File.prototype.slice;
    let delayed = false;
    File.prototype.slice = function (...args) {
      const blob = slice.apply(this, args);
      if (this.name === '느린영상.mp4' && !delayed) {
        delayed = true;
        const read = blob.arrayBuffer.bind(blob);
        blob.arrayBuffer = () =>
          new Promise<ArrayBuffer>((resolve) => {
            window.addEventListener(
              'finish-slow-file',
              () => {
                void read().then(resolve);
              },
              { once: true },
            );
          });
      }
      return blob;
    };
  });
  await openRegistration(page);
  const input = page.locator('#video-file');
  await input.setInputFiles({ name: '느린영상.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.getByRole('button', { name: '파일 확인 중…', exact: true })).toBeDisabled();
  await input.setInputFiles({ name: '최신영상.mp4', mimeType: 'video/mp4', buffer: videoBytes });
  await expect(page.locator('#video-selection')).toContainText('최신영상.mp4');
  await page.evaluate(() => window.dispatchEvent(new Event('finish-slow-file')));
  await expect(page.getByRole('button', { name: '등록', exact: true })).toBeEnabled();
  await expect(page.locator('#video-selection')).toContainText('최신영상.mp4');
  await expect(page.locator('#video-selection')).not.toContainText('느린영상.mp4');
});
