import { expect, test, type Locator } from '@playwright/test';

import { searchFixture } from './search-fixture';

for (const width of [1440, 390]) {
  test(`검색 결과 공통 메뉴와 사이드바를 ${width}px에서 사용한다`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
    await page.goto('/search/results?q=장면&broadcastFrom=2026-09-01&broadcastTo=2026-09-03');
    await expect(page.getByRole('heading', { name: '관련 장면 1개' })).toBeVisible();
    const header = page.getByRole('banner');
    const searchInput = header.getByRole('textbox', { name: '뉴스 장면 검색어' });
    const account = header.getByRole('button', { name: /e2e-editor/ });
    const initialAccountBounds = (await account.boundingBox())!;
    const initialSearchBounds = (await searchInput.boundingBox())!;
    await expect(header).toHaveCSS('position', 'fixed');
    expect(initialSearchBounds.y + initialSearchBounds.height / 2).toBeCloseTo(
      initialAccountBounds.y + initialAccountBounds.height / 2,
      0,
    );

    const sidebar = page.getByRole('complementary', { name: '검색 도구', exact: true });
    await expect(sidebar).toHaveCount(1);
    await expect(
      sidebar.getByRole('button', { name: /방송일 2026\.09\.01 – 2026\.09\.03/ }),
    ).toBeVisible();
    expect((await sidebar.boundingBox())!.x + (await sidebar.boundingBox())!.width).toBeLessThan(
      (await page.getByRole('main').boundingBox())!.x,
    );
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await page.screenshot({ path: testInfo.outputPath('results.png'), fullPage: true });

    await account.click();
    const menu = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect(menu.getByRole('link', { name: '장면 검색' })).toBeVisible();
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeInViewport();
    await page.keyboard.press('Escape');
    await expect(account).toBeFocused();
    await expect(menu).not.toBeVisible();

    await sidebar.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
    const history = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
    await expect(history.getByRole('region', { name: '이전 검색 기록 목록' })).toBeVisible();
    await history.getByRole('button', { name: '정보 패널 닫기' }).click();
    await expect(history).not.toBeVisible();

    await page.route('**/api/v1/search', (route) =>
      route.fulfill({
        status: 404,
        json: {
          isSuccess: false,
          code: 'COMM_404',
          message: '요청한 항목을 찾을 수 없습니다.',
          requestId: 'layout-error',
        },
      }),
    );
    await page.getByRole('button', { name: '검색', exact: true }).click();
    await page.getByRole('button', { name: '오류 알림 닫기' }).click();
    await expect(
      page.getByRole('heading', { name: '검색 결과를 불러오지 못했어요' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '같은 조건으로 다시 시도' })).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath('results-frosted-error.png'),
      fullPage: true,
    });
    await searchInput.click();
    await expect(page.getByRole('textbox', { name: '뉴스 장면 검색어' })).toBeFocused();
    await expect(page).toHaveURL((url) => url.searchParams.get('broadcastFrom') === '2026-09-01');
    const statePanel = page.locator('[role="alert"][data-state="failed"]');
    const stateBounds = (await statePanel.boundingBox())!;
    const sidebarBounds = (await sidebar.boundingBox())!;
    expect(stateBounds.y + stateBounds.height).toBeCloseTo(
      sidebarBounds.y + sidebarBounds.height,
      0,
    );
    await statePanel.evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    expect((await account.boundingBox())!.y).toBeCloseTo(initialAccountBounds.y, 0);
    expect((await searchInput.boundingBox())!.y).toBeCloseTo(initialSearchBounds.y, 0);
    await expect(searchInput).toBeInViewport();
    await page.screenshot({ path: testInfo.outputPath('results-error-scrolled.png') });
    await account.click();
    await expect(menu).toBeVisible();
    await page.keyboard.press('Escape');
    await searchInput.click();
    await expect(menu).not.toBeVisible();
    await expect(searchInput).toBeFocused();
  });
}

const shortSceneDescription = '서울역 대합실 전경';
const longSceneDescription =
  '설 연휴 첫날 이른 아침부터 서울역 대합실과 승강장을 가득 메운 귀성객들이 열차를 기다리며 짐을 들고 이동하는 모습과 안내 전광판을 확인하는 장면';
const manyKeywords = [
  '설 연휴 귀성',
  '서울역 대합실',
  '열차 승강장',
  '귀성객 인파',
  '안내 전광판',
  '이동 통로',
];

// 잘린 제목은 보이는 상자보다 실제 글줄이 높다. 상자 높이만 재면 고정 높이 때문에 항상 같아 무의미하다.
function isTitleFullyVisible(title: Locator) {
  return title.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    return range.getBoundingClientRect().height <= element.clientHeight + 0.5;
  });
}

for (const width of [1440, 390]) {
  test(`결과 카드 높이는 제목 길이와 키워드 개수에 흔들리지 않는다 (${width}px)`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
    const descriptions = [shortSceneDescription, longSceneDescription, '귀성 차량 행렬'];
    // 카드 높이 불변식을 지키려면 테두리가 붙는 확장어 칩이 실제로 섞여야 한다 — 전부 user 로 두면
    // 테두리가 높이를 밀어 올리는 회귀를 이 테스트가 놓친다 (S15P21A501-234).
    const keywordSets = [
      [{ keyword: '장면', origin: 'user' }],
      [{ keyword: '서울역', origin: 'expanded' }],
      manyKeywords.map((keyword, index) => ({
        keyword,
        origin: index % 2 === 0 ? 'user' : 'expanded',
      })),
    ];
    await page.route('**/api/v1/search', (route) =>
      route.fulfill({
        status: 200,
        json: {
          isSuccess: true,
          code: 'COMM_200',
          message: '성공',
          data: {
            ...searchFixture,
            results: descriptions.map((description, index) => ({
              ...searchFixture.results[0],
              search_result_id: String(101 + index),
              scene_id: String(31 + index),
              rank: index + 1,
              scene_description: description,
              matched_keywords: keywordSets[index],
            })),
          },
        },
      }),
    );
    await page.goto('/search/results?q=장면&broadcastFrom=2026-09-01&broadcastTo=2026-09-03');
    await expect(page.getByRole('heading', { name: '관련 장면 3개' })).toBeVisible();

    const cards = page.locator('#search-results article');
    await expect(cards).toHaveCount(3);
    const heights = await cards.evaluateAll((elements) =>
      elements.map((element) => element.getBoundingClientRect().height),
    );
    expect(Math.max(...heights) - Math.min(...heights)).toBeLessThan(1);

    const longTitle = page.getByRole('heading', { level: 3, name: longSceneDescription });
    await expect(longTitle).toHaveCSS('-webkit-line-clamp', '2');
    await expect(longTitle).toHaveText(longSceneDescription);
    expect(await isTitleFullyVisible(longTitle)).toBe(false);
    expect(
      await isTitleFullyVisible(
        page.getByRole('heading', { level: 3, name: shortSceneDescription }),
      ),
    ).toBe(true);

    await expect(
      page.getByRole('button', { name: `2위 ${longSceneDescription} Preview 열기` }),
    ).toHaveAttribute('title', `${longSceneDescription}\n키워드: 서울역(확장)`);
    await expect(
      page.getByRole('button', { name: '3위 귀성 차량 행렬 Preview 열기' }),
    ).toHaveAttribute(
      'title',
      `귀성 차량 행렬\n키워드: ${manyKeywords
        .map((keyword, index) => (index % 2 === 0 ? keyword : `${keyword}(확장)`))
        .join(', ')}`,
    );

    const crowdedCard = cards.nth(2);
    const badge = crowdedCard.getByText('자동 인식', { exact: true });
    await expect(badge).toBeVisible();
    const badgeBounds = (await badge.boundingBox())!;
    const cardBounds = (await crowdedCard.boundingBox())!;
    expect(badgeBounds.x + badgeBounds.width).toBeLessThanOrEqual(cardBounds.x + cardBounds.width);
  });
}
