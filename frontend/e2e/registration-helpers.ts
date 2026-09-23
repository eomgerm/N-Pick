import { expect, type Page } from '@playwright/test';

// 등록 화면 h1 은 마운트 effect 에서 포커스를 받는다. 그 전에 파일을 넣으면 hydration 전이라 change 이벤트를 놓친다.
export async function openRegistration(page: Page) {
  await page.goto('/review?view=upload');
  await expect(page.getByRole('heading', { name: '영상 등록', level: 1 })).toBeFocused();
}

// 영상을 고르기 전에는 나머지 입력이 잠겨 있다.
export async function openRegistrationWithVideo(page: Page) {
  await openRegistration(page);
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  await expect(page.locator('#video-selection')).toContainText('preview-fixture.mp4');
}
