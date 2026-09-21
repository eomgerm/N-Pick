import { expect, test, type Locator, type Page } from '@playwright/test';

const viewports = [
  { width: 2560, height: 1440 },
  { width: 1920, height: 1080 },
  { width: 1440, height: 900 },
  { width: 1366, height: 768 },
  { width: 1280, height: 600 },
  { width: 1024, height: 600 },
  { width: 1000, height: 900 },
  { width: 768, height: 1024 },
  { width: 844, height: 390 },
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

function roleLinks(page: Page) {
  return [
    page.getByRole('link', { name: /편집 기사로 시작하기/ }),
    page.getByRole('link', { name: /아카이브 팀으로 시작하기/ }),
  ];
}

async function visibleOpacity(locator: Locator) {
  return locator.evaluate((element) => {
    let opacity = 1;
    for (let node: Element | null = element; node; node = node.parentElement) {
      opacity *= Number(getComputedStyle(node).opacity);
    }
    return opacity;
  });
}

async function showRoles(page: Page) {
  await page.evaluate(() =>
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }),
  );
  await expect(page.getByRole('heading', { name: '어떤 작업을 시작할까요?' })).toBeVisible();
  await expect
    .poll(() => visibleOpacity(page.getByRole('heading', { name: '어떤 작업을 시작할까요?' })))
    .toBeGreaterThan(0.99);
}

async function expectReadableLayout(page: Page) {
  const viewport = page.viewportSize()!;
  const title = page.getByRole('heading', { name: '어떤 작업을 시작할까요?' });
  const footer = page.locator('main footer');
  const links = roleLinks(page);
  const elements = [title, ...links, footer];
  const bounds = await Promise.all(elements.map((element) => element.boundingBox()));
  for (const box of bounds) {
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(-1);
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width + 1);
  }
  for (let first = 0; first < bounds.length; first++) {
    for (let second = first + 1; second < bounds.length; second++) {
      const a = bounds[first]!;
      const b = bounds[second]!;
      const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
      const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
      expect(overlapX > 1 && overlapY > 1, `레이아웃 요소 ${first}, ${second} 겹침`).toBe(false);
    }
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width);
  for (const link of links) {
    await link.scrollIntoViewIfNeeded();
    await expect(link).toBeInViewport();
    await link.click({ trial: true });
    await expect.poll(() => visibleOpacity(link)).toBeGreaterThan(0.99);
    const clippedContent = await link.evaluate((card) => {
      const bounds = card.getBoundingClientRect();
      return [...card.querySelectorAll('strong, span')]
        .filter((child) => child.getClientRects().length > 0)
        .filter((child) => {
          const rect = child.getBoundingClientRect();
          return (
            rect.left < bounds.left - 1 ||
            rect.right > bounds.right + 1 ||
            rect.top < bounds.top - 1 ||
            rect.bottom > bounds.bottom + 1
          );
        })
        .map((child) => child.textContent);
    });
    expect(clippedContent).toEqual([]);
  }
  await footer.scrollIntoViewIfNeeded();
  await expect(footer).toBeInViewport();
}

test.describe('랜딩 반응형 배치', () => {
  test.use({ reducedMotion: 'reduce' });

  for (const viewport of viewports) {
    test(`${viewport.width}×${viewport.height}: 문구·역할 카드·푸터가 잘리거나 겹치지 않는다`, async ({
      page,
    }, testInfo) => {
      await page.setViewportSize(viewport);
      await page.goto('/landing');
      await expect(page.locator('[data-intro]')).toHaveAttribute('data-intro', 'done');
      const brand = (await page.locator('#landing-title').locator('..').boundingBox())!;
      expect(brand.x).toBeGreaterThanOrEqual(-1);
      expect(brand.x + brand.width).toBeLessThanOrEqual(viewport.width + 1);
      await showRoles(page);
      await expectReadableLayout(page);
      await expect(page.getByText('필요한 뉴스 장면을 빠르게 찾아보세요.')).toBeVisible();
      await expect(page.getByText('검수가 필요한 장면을 확인해 주세요.')).toBeVisible();
      await expect(page.locator('video')).toHaveJSProperty('paused', true);
      await page.screenshot({ path: testInfo.outputPath('landing.png'), fullPage: true });
    });
  }
});

test('넓은 화면의 역할 배경은 화면 전체 폭이며 카드와 안내가 함께 나타난다', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto('/landing');
  await page.evaluate(() =>
    window.scrollTo({ top: window.innerHeight * 0.9, behavior: 'instant' }),
  );
  const roles = page.getByRole('region', { name: '어떤 작업을 시작할까요?' });
  await expect.poll(() => visibleOpacity(roles.getByRole('heading'))).toBeGreaterThan(0.99);
  const box = (await roles.boundingBox())!;
  expect(box.x).toBeCloseTo(0, 0);
  expect(box.width).toBe(1920);
  await expectReadableLayout(page);
});

test('역할 화면에서 폭과 높이를 바꿔도 새로고침 없이 정상 배치로 전환한다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/landing');
  await showRoles(page);
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 390, height: 844 },
    { width: 1440, height: 900 },
    { width: 1440, height: 500 },
    { width: 1440, height: 900 },
    { width: 1920, height: 1080 },
  ]) {
    await page.setViewportSize(viewport);
    const title = page.getByRole('heading', { name: '어떤 작업을 시작할까요?' });
    await expect.poll(() => visibleOpacity(title)).toBeGreaterThan(0.99);
    await expect(title).toBeInViewport();
    await expectReadableLayout(page);
  }
});

for (const [index, role] of ['editor', 'reviewer'].entries()) {
  test(`좁은 화면에서도 ${role} 로그인으로 이동한다`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 390, height: 600 });
    await page.goto('/landing');
    await showRoles(page);
    await roleLinks(page)[index].click();
    await expect(page).toHaveURL(new RegExp(`/login\\?role=${role}$`));
    await expect(page.getByLabel('아이디', { exact: true })).toBeVisible();
  });
}
