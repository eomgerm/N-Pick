import { expect, test } from '@playwright/test';
import { searchFixture } from './search-fixture';

const success = (data: unknown) => ({ isSuccess: true, code: 'COMM_200', message: '성공', data });
function item(id = '100') {
  const first = searchFixture.results[0];
  return {
    search_execution_id: id,
    query_text: `서버 검색어 ${id}`,
    created_at: '2026-08-01T03:00:00Z',
    explicit_filters: { broadcast_date: { from: '2026-08-01', to: '2026-08-10' } },
    status: 'succeeded',
    snapshot_status: 'available',
    result_count: 1,
    representative_result: {
      search_result_id: first.search_result_id,
      scene_id: first.scene_id,
      clip_id: first.clip_id,
      display_name: first.display_name,
      scene_description: first.scene_description,
      start_time_ms: first.start_time_ms,
      end_time_ms: first.end_time_ms,
      rank: 1,
    },
  };
}
function listPage(items: unknown[], page = 0, total = items.length) {
  return {
    items,
    page,
    size: 10,
    total_elements: total,
    total_pages: Math.ceil(total / 10),
    has_next: (page + 1) * 10 < total,
  };
}
function detail(id = '100') {
  // search_snapshot 은 searchFixture 를 그대로 쓰되, 그 자신의 search_execution_id(고정값 '100')를
  // 요청한 기록 id로 맞춘다 — 서버 계약(parseMySearchHistoryDetail)이 둘의 일치를 요구한다.
  return {
    ...item(id),
    search_snapshot: { ...structuredClone(searchFixture), search_execution_id: id },
  };
}

for (const role of ['editor', 'reviewer']) {
  test(`${role} 검색 기록은 서버 목록·페이지를 조회하고 당시 결과 화면으로 이동한다`, async ({
    page,
  }) => {
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: `e2e-${role}`, url: 'http://127.0.0.1:3116' }]);
    let listReads = 0;
    let postSearches = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/search')) postSearches++;
    });
    await page.route('**/api/v1/search/history?**', (route) => {
      listReads++;
      expect(route.request().headers().cookie).toContain(`JSESSIONID=e2e-${role}`);
      const pageIndex = Number(new URL(route.request().url()).searchParams.get('page'));
      return route.fulfill({
        json: success(
          listPage(
            pageIndex === 0
              ? Array.from({ length: 10 }, (_, i) => item(String(100 + i)))
              : [item('110')],
            pageIndex,
            11,
          ),
        ),
      });
    });
    await page.goto('/search');
    expect(listReads).toBe(0);
    await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
    const panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
    await expect(panel.getByRole('listitem')).toHaveCount(10);
    await expect(panel.getByText('2026. 8. 1.', { exact: true }).first()).toBeVisible();
    await panel.getByRole('button', { name: '다음 검색 기록 페이지', exact: true }).click();
    await expect(panel.getByRole('listitem')).toHaveCount(1);
    await expect(panel.getByText('서버 검색어 110', { exact: true })).toBeVisible();
    await expect(
      panel.getByRole('button', { name: '다음 검색 기록 페이지', exact: true }),
    ).toBeDisabled();
    await panel.getByRole('button', { name: '이전 검색 기록 페이지', exact: true }).click();

    // 기록 행을 누르면 그 자리에서 검색창만 채우던 옛 동작 대신, 당시 결과 화면(historyId)으로
    // 이동해 스냅샷을 그대로 보여준다 — 이 MR(S15P21A501-262)이 새로 만든 동작이다.
    // 상세(gate) 플로우가 쓰는 id 100 과 겹치지 않도록 목록의 다른 항목(105)을 쓴다.
    await page.route('**/api/v1/search/history/105', (route) =>
      route.fulfill({ json: success(detail('105')) }),
    );
    const row = panel.getByRole('button', { name: /^서버 검색어 105 검색 결과/ });
    await row.click();
    await expect(panel).not.toBeVisible();
    await expect(page).toHaveURL(/\/search\/results\?historyId=105$/);
    expect(postSearches).toBe(0);
    // 검색 기록(스냅샷) 모드 컨텍스트: 배지 날짜 + 재검색 고지로 라이브 검색과 구분한다
    // (S15P21A501-262). role="status" 는 name-from-content 가 아니라 hasText 로
    // "검색 해석: ..." status 와 구분한다.
    const historyContext = page.getByRole('status').filter({ hasText: '검색 기록' });
    await expect(historyContext).toContainText('2026-08-01 검색 기록');
    await expect(historyContext).toContainText(
      '다시 검색하면 지금 기준으로 새로 찾은 결과가 나와요',
    );
    // 결과 화면의 검색창은 compact 변형(type 없음 → textbox)이라 진입 화면의 searchbox 와 role 이 다르다.
    await expect(page.getByRole('textbox', { name: '뉴스 장면 검색어' })).toHaveValue(
      '서버 검색어 105',
    );
    await expect(page.getByRole('heading', { name: '관련 장면 1개', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
    await expect(
      panel.getByRole('button', { name: /검색 기록 상세 보기/, exact: true }),
    ).toHaveCount(0);
    expect(postSearches).toBe(0);
  });
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
});

test('목록 로딩·오류·빈 기록을 구분하고 재시도한다', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let reads = 0;
  await page.route('**/api/v1/search/history?**', async (route) => {
    await gate;
    return ++reads === 1
      ? route.fulfill({
          status: 500,
          json: { isSuccess: false, code: 'COMM_500', message: '검색 기록 조회 실패' },
        })
      : route.fulfill({ json: success(listPage([])) });
  });
  await page.goto('/search');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  const panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  await expect(panel.getByRole('status')).toHaveText('검색 기록을 불러오는 중…');
  release();
  await expect(panel.getByRole('alert')).toContainText('검색 기록 조회 실패');
  await panel.getByRole('button', { name: '검색 기록 다시 시도', exact: true }).click();
  await expect(panel.getByText('아직 검색 기록이 없습니다.')).toBeVisible();
  await expect(panel.getByRole('listitem')).toHaveCount(0);
});

test('0건 검색·복원 불가·기록 404를 결과 화면에서 구분한다', async ({ page }) => {
  const zero = { ...item('100'), result_count: 0, representative_result: null };
  const missing = {
    ...item('200'),
    snapshot_status: 'unavailable',
    result_count: null,
    representative_result: null,
    explicit_filters: null,
  };
  await page.route('**/api/v1/search/history?**', (route) =>
    route.fulfill({ json: success(listPage([zero, missing])) }),
  );
  await page.route('**/api/v1/search/history/100', (route) =>
    route.fulfill({
      json: success({ ...zero, search_snapshot: { ...searchFixture, results: [] } }),
    }),
  );
  let reads = 0;
  await page.route('**/api/v1/search/history/200', (route) =>
    ++reads === 1
      ? route.fulfill({
          status: 404,
          json: {
            isSuccess: false,
            code: 'SRCH_404_001',
            message: '검색 기록을 찾을 수 없습니다.',
          },
        })
      : route.fulfill({ json: success({ ...missing, search_snapshot: null }) }),
  );
  await page.goto('/search');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  let panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  await panel.getByRole('button', { name: /^서버 검색어 100 검색 결과/ }).click();
  await expect(page).toHaveURL(/\/search\/results\?historyId=100$/);
  await expect(page.getByRole('heading', { name: '관련 장면 0개', exact: true })).toBeVisible();
  await expect(page.getByText('조건에 맞는 장면이 없어요', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  await panel.getByText('서버 검색어 200', { exact: true }).click();
  await expect(page.getByText('이 검색 기록을 찾을 수 없어요.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '같은 조건으로 다시 시도', exact: true }).click();
  await expect(
    page
      .locator('#search-results')
      .getByText('이 검색의 당시 결과 기록이 없어 결과를 표시할 수 없어요.', {
        exact: true,
      }),
  ).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(0);
});

test('새 검색 뒤 패널 재열기와 새로고침은 실제 기록을 다시 조회한다', async ({ page }) => {
  let saved = false;
  let posts = 0;
  let listReads = 0;
  await page.route('**/api/v1/search/history?**', (route) => {
    listReads++;
    return route.fulfill({ json: success(listPage(saved ? [item()] : [])) });
  });
  await page.route('**/api/v1/search', (route) => {
    saved = true;
    posts++;
    return route.fulfill({ json: success(searchFixture) });
  });
  await page.goto('/search');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  await expect(page.getByText('아직 검색 기록이 없습니다.')).toBeVisible();
  await page.keyboard.press('Escape');
  await page.goto('/search/results?q=서버검색');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  await expect(page.getByText('서버 검색어 100', { exact: true })).toBeVisible();
  const previousReads = listReads;
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  await expect.poll(() => listReads).toBeGreaterThan(previousReads);
  expect(posts).toBe(1);
  await page.goto('/search');
  await page.reload();
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  await expect(page.getByText('서버 검색어 100', { exact: true })).toBeVisible();
  expect(posts).toBe(1);
});

test('검색 기록 삭제는 확인 모달을 거치고 취소하면 그대로 남는다 (S15P21A501-277)', async ({
  page,
}) => {
  let removed = false;
  let deleteCalls = 0;
  await page.route('**/api/v1/search/history?**', (route) =>
    route.fulfill({
      json: success(listPage(removed ? [item('200')] : [item('100'), item('200')])),
    }),
  );
  await page.route('**/api/v1/search/history/100', (route) => {
    if (route.request().method() !== 'DELETE') return route.fallback();
    deleteCalls++;
    removed = true;
    return route.fulfill({ json: { isSuccess: true, code: 'COMM_200', message: '성공' } });
  });

  await page.goto('/search');
  await page.getByRole('button', { name: '이전 검색 기록', exact: true }).click();
  const panel = page.getByRole('complementary', { name: '이전 검색 기록', exact: true });
  const remove = panel.getByRole('button', { name: '서버 검색어 100 검색 기록 삭제', exact: true });

  // 버튼만 눌러서는 지워지지 않는다. 휴지통이 없으므로 확인을 거친다.
  await remove.click();
  const confirm = page.getByRole('dialog', { name: '검색 기록을 지울까요?', exact: true });
  await expect(confirm).toBeVisible();
  await expect(confirm.getByText('서버 검색어 100', { exact: true })).toBeVisible();
  // 되돌릴 수 없는 동작이라 기본 포커스는 취소에 있다.
  await expect(confirm.getByRole('button', { name: '취소', exact: true })).toBeFocused();

  await confirm.getByRole('button', { name: '취소', exact: true }).click();
  await expect(confirm).not.toBeVisible();
  expect(deleteCalls).toBe(0);
  await expect(panel.getByText('서버 검색어 100', { exact: true })).toBeVisible();

  await remove.click();
  await page
    .getByRole('dialog', { name: '검색 기록을 지울까요?', exact: true })
    .getByRole('button', { name: '삭제', exact: true })
    .click();

  await expect(page.getByRole('dialog', { name: '검색 기록을 지울까요?' })).not.toBeVisible();
  await expect(panel.getByText('서버 검색어 100', { exact: true })).toHaveCount(0);
  await expect(panel.getByText('서버 검색어 200', { exact: true })).toBeVisible();
  expect(deleteCalls).toBe(1);
  // 포커스를 잃으면 키보드 사용자가 목록 밖으로 튕긴다. 지운 행의 버튼은 이미 사라진 뒤다.
  await expect(
    panel.getByRole('region', { name: '이전 검색 기록 목록', exact: true }),
  ).toBeFocused();
});
