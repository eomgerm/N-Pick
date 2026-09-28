import { expect, test, type Page, type Route } from '@playwright/test';

import { addPendingTags, mockCorrectionCandidates } from './correction-candidates-mock';

function inquiry(feedbackId = '41', status = 'OPEN') {
  return {
    feedbackId,
    status,
    resolution: null as string | null,
    resolutionNote: null,
    createdAt: '2026-09-09T01:00:00Z',
    queryText: '귀성길 정체',
    sceneId: '31',
    scene: {
      sceneId: '31',
      clipId: '21',
      clipTitle: `저녁 뉴스 ${feedbackId}`,
      startTimeMs: 42000,
      endTimeMs: 49000,
      pipelineRunId: '11',
      processingNo: 1,
    },
    hasComment: true,
    comment: '다른 장면 같습니다.',
    resultRank: 1,
    resultExplainJson: '{"score":0.8}',
    execution: {
      queryText: '귀성길 정체',
      explicitFiltersJson: '{}',
      parsedQueryJson: null as string | null,
      resolverOutputJson: null,
      appliedRulesJson: '[]',
      appliedExcludesJson: '[]',
    },
    evidence: [] as Array<{
      taggingId: string;
      tagType: string;
      matchValue: string;
      tagName: string;
      sources: string[];
      verifiedState: string | null;
      scope: string;
    }>,
    history: {
      reviewedById: status === 'OPEN' ? null : '2',
      reviewerName: status === 'OPEN' ? null : '서버 담당자',
      reviewerLoginId: status === 'OPEN' ? null : 'other-reviewer',
      reviewStartedAt: status === 'OPEN' ? null : '2026-09-09T02:00:00Z',
      verifiedByExecutionId: null,
    },
  };
}

async function success(route: Route, data?: unknown) {
  await route.fulfill({
    json: { isSuccess: true, code: 'COMM_200', message: '요청에 성공했습니다.', data },
  });
}

async function failure(route: Route, status: number, code: string) {
  await route.fulfill({
    status,
    json: { isSuccess: false, code, message: '요청을 처리할 수 없습니다.' },
  });
}

async function reviewer(page: Page) {
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
}

async function mockList(page: Page, getItems: () => ReturnType<typeof inquiry>[]) {
  const requests: URL[] = [];
  await page.route('**/api/v1/review/inquiries?*', async (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    const items = getItems();
    const status = url.searchParams.get('status');
    const filtered = items.filter((item) => !status || item.status === status);
    const currentPage = Number(url.searchParams.get('page'));
    const size = Number(url.searchParams.get('size'));
    await success(route, {
      items: filtered.slice(currentPage * size, (currentPage + 1) * size),
      page: currentPage,
      size,
      totalElements: filtered.length,
      totalPages: Math.ceil(filtered.length / size),
      statusCounts: {
        open: items.filter((item) => item.status === 'OPEN').length,
        reviewing: items.filter((item) => item.status === 'REVIEWING').length,
        closed: items.filter((item) => item.status === 'CLOSED').length,
      },
    });
  });
  return requests;
}

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 390, height: 844 },
]) {
  test(`빈 문의 패널과 사이드바의 상하단이 ${viewport.width}×${viewport.height}에서 정렬된다`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    await reviewer(page);
    await mockList(page, () => []);
    await page.goto('/review?view=inquiries');
    await expect(
      page.getByRole('heading', { name: '해당 상태 문의 없음', exact: true }),
    ).toBeVisible();
    await page.evaluate(() => document.fonts.ready);

    const sidebar = page.getByRole('complementary', { name: '검수 도구' });
    const panel = page.getByRole('region', { name: '문의 목록', exact: true });
    const sidebarBounds = (await sidebar.boundingBox())!;
    const panelBounds = (await panel.boundingBox())!;
    expect(panelBounds.y).toBeCloseTo(sidebarBounds.y, 0);
    expect(panelBounds.y + panelBounds.height).toBeCloseTo(
      sidebarBounds.y + sidebarBounds.height,
      0,
    );
    expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(viewport.height);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width);
    await page.screenshot({ path: testInfo.outputPath('empty-review.png'), fullPage: true });
  });
}

for (const width of [1440, 1024, 390]) {
  test(`긴 처리 사유가 ${width}px에서 문의 패널 높이를 늘리지 않고 스크롤된다`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await reviewer(page);
    await page.route('**/api/v1/review/inquiries/41', (route) =>
      success(route, {
        ...inquiry('41', 'CLOSED'),
        resolution: 'no_action',
        resolutionNote: '검색어와 장면을 대조했으며 추가 수정이 필요하지 않습니다.\n'.repeat(100),
      }),
    );
    await page.goto('/review?inquiry=41');
    const note = page.getByRole('region', { name: '처리 사유', exact: true });
    await expect(note).toBeVisible();
    await page.evaluate(() => document.fonts.ready);

    const context = page.getByRole('region', { name: '문의 내용', exact: true }).locator('..');
    const scene = page.getByRole('region', { name: '문의 장면', exact: true });
    const actions = page.getByRole('complementary', { name: '문의 검수' });
    const contextBounds = (await context.boundingBox())!;
    const sceneBounds = (await scene.boundingBox())!;
    const actionsBounds = (await actions.boundingBox())!;
    expect(actionsBounds.height).toBeCloseTo(contextBounds.height, 0);
    expect(sceneBounds.y + sceneBounds.height).toBeCloseTo(
      contextBounds.y + contextBounds.height,
      0,
    );
    expect(await note.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(
      true,
    );
    expect(await note.evaluate((element) => element.clientHeight)).toBeGreaterThan(40);
    await note.focus();
    const resultHeading = page.getByRole('heading', { name: '처리 결과', exact: true });
    // 두 좌표를 한 프레임에서 함께 읽는다. 좁은 화면에서는 focus 가 문서를 smooth 스크롤하므로
    // boundingBox 를 따로 부르면 그 사이 문서가 움직여 오프셋이 어긋나 보인다.
    const headingOffset = () =>
      resultHeading.evaluate(
        (heading) =>
          heading.getBoundingClientRect().top -
          heading.closest('aside')!.getBoundingClientRect().top,
      );
    const headingOffsetBefore = await headingOffset();
    await note.press('PageDown');
    await expect.poll(() => note.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
    expect(await headingOffset()).toBeCloseTo(headingOffsetBefore, 0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await page.screenshot({ path: testInfo.outputPath('inquiry-note-scroll.png'), fullPage: true });
  });
}

// 검수 중 문의는 문의 화면의 status 필터가 담당한다. 처리 현황 화면에는 문의 탭이 없다.
test('검수 중 문의는 실제 목록·상세를 조회하고 같은 필터로 복귀한다', async ({
  page,
}, testInfo) => {
  await reviewer(page);
  const requests = await mockList(page, () => [inquiry('41', 'REVIEWING')]);
  await page.route('**/api/v1/review/inquiries/41', (route) =>
    success(route, inquiry('41', 'REVIEWING')),
  );
  await page.goto('/review?status=reviewing');
  await expect(page.getByRole('button', { name: /문의 #41/ })).toBeVisible();
  await page.getByRole('button', { name: /문의 #41/ }).click();
  await expect(page.getByText(/서버 담당자/)).toBeVisible();
  const header = page.getByRole('banner');
  const title = header.getByRole('heading', { name: '문의 상세', exact: true });
  const backButton = header.getByRole('button', { name: '문의 목록으로', exact: true });
  await expect(page.getByRole('main').getByRole('button', { name: '문의 목록으로' })).toHaveCount(
    0,
  );
  await expect(page.getByText(/문의 상세\s*\/\s*#/)).toHaveCount(0);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await expect(title).toBeVisible();
    await expect(backButton).toBeInViewport();
    const titleBounds = (await title.boundingBox())!;
    const buttonBounds = (await backButton.boundingBox())!;
    expect(buttonBounds.x + buttonBounds.width).toBeCloseTo(width - (width > 760 ? 32 : 14), 0);
    expect(titleBounds.y + titleBounds.height / 2).toBeCloseTo(
      buttonBounds.y + buttonBounds.height / 2,
      0,
    );
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await page.screenshot({ path: testInfo.outputPath(`inquiry-detail-header-${width}.png`) });
  }
  await backButton.click();
  await expect(page).toHaveURL('/review?status=reviewing');
  await expect(page.getByRole('button', { name: /문의 #41/ })).toBeVisible();
  expect(requests.every((url) => url.searchParams.get('status') === 'REVIEWING')).toBe(true);
});

test('선점 충돌 후 최신 상태를 읽으면 이전 오류를 지우고 목록도 갱신한다', async ({ page }) => {
  await reviewer(page);
  let current = inquiry();
  let detailReads = 0;
  const listReads = await mockList(page, () => [current]);
  await page.route('**/api/v1/review/inquiries/41', (route) => {
    detailReads++;
    return success(route, current);
  });
  await page.route('**/api/v1/review/inquiries/41/claim', async (route) => {
    current = inquiry('41', 'REVIEWING');
    await failure(route, 409, 'FEEDBACK_409_001');
  });

  await page.goto('/review?status=open');
  await page.getByRole('button', { name: /문의 #41/ }).click();
  await page.getByRole('button', { name: '검수 시작', exact: true }).click();
  const error = page.getByRole('region', { name: '검수 시작 실패 안내' });
  await expect(error).toBeVisible();
  await error.getByRole('button', { name: '최신 상태 확인' }).click();
  await expect(page.getByText(/서버 담당자/)).toBeVisible();
  await expect(
    page.getByRole('heading', { name: '다른 아카이브 팀이 처리 중입니다.' }),
  ).toBeVisible();
  await expect(error).not.toBeVisible();
  expect(detailReads).toBeGreaterThanOrEqual(2);
  await page.getByRole('button', { name: '문의 목록으로', exact: true }).click();
  await expect(page).toHaveURL(/\/review\?status=open$/);
  await expect(
    page.getByRole('heading', { name: '해당 상태 문의 없음', exact: true }),
  ).toBeVisible();
  expect(listReads.length).toBeGreaterThanOrEqual(2);
});

test('담당자 정보가 없는 검수 중 문의는 다른 담당자로 단정하지 않고 저장을 막는다', async ({
  page,
}) => {
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.history.reviewerLoginId = null;
  current.history.reviewerName = null;
  current.history.reviewedById = null;
  await mockList(page, () => [current]);
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.goto('/review?inquiry=41');

  await expect(
    page.getByRole('heading', { name: '담당자 정보를 확인할 수 없습니다.' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: '다른 아카이브 팀이 처리 중입니다.' }),
  ).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: '처리 결과' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^(문의 종료|판정 저장)$/ })).toHaveCount(0);
});

test('담당 검수자는 여러 태그 추가 후보를 만들고 기존 태그 삭제 후보를 만든다', async ({
  page,
}) => {
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.resolution = 'correction';
  current.history.reviewerLoginId = 'e2e-reviewer';
  current.history.reviewerName = 'E2E 검수자';
  current.evidence = [
    {
      taggingId: '51',
      tagType: 'location',
      matchValue: '서울역',
      tagName: '서울역',
      sources: ['ocr'],
      verifiedState: 'verified',
      scope: 'SCENE',
    },
  ];
  const operations: unknown[][] = [];
  const candidates = await mockCorrectionCandidates(page);
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    const body = route.request().postDataJSON();
    operations.push(body.operations);
    const evidenceIds = body.operations.map((_: unknown, index: number) =>
      String(61 + operations.length * 10 + index),
    );
    addPendingTags(candidates, body.operations, evidenceIds, { 서울역: '51' });
    await success(route, { feedbackId: '41', created: body.operations.length, evidenceIds });
  });

  await page.goto('/review?inquiry=41');
  const tags = page.getByRole('list', { name: '현재 장면과 영상의 태그' });
  const toast = page.getByText('태그 교정 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');

  // 추가 칩은 하나씩 확정한다 — ✓ 버튼, Enter, 다른 범위·유형 순으로 모두 같은 저장 경로를 탄다.
  const tagType = page.getByRole('combobox', { name: '태그 유형', exact: true });
  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await tagType.selectOption('keyword');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('서울');
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();
  await expect(tags.getByText('서울', { exact: true })).toBeVisible();
  await expect(toast).toBeVisible();

  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await tagType.selectOption('keyword');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('부산');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).press('Enter');
  await expect(tags.getByText('부산', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '+ 영상 전체', exact: true }).click();
  await page.getByRole('combobox', { name: '태그 유형', exact: true }).selectOption('location');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('광주');
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();
  await expect(tags.getByText('광주', { exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '태그 값', exact: true })).toHaveCount(0);
  expect(operations).toEqual([
    [
      {
        action: 'APPROVE',
        scope: 'SCENE',
        tagType: 'keyword',
        matchValue: '서울',
        displayName: '서울',
      },
    ],
    [
      {
        action: 'APPROVE',
        scope: 'SCENE',
        tagType: 'keyword',
        matchValue: '부산',
        displayName: '부산',
      },
    ],
    [
      {
        action: 'APPROVE',
        scope: 'CLIP',
        tagType: 'location',
        matchValue: '광주',
        displayName: '광주',
      },
    ],
  ]);

  await page.getByRole('button', { name: '‘서울역’ 삭제 후보', exact: true }).click();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소', exact: true })).toBeVisible();
  await expect(tags.getByText('서울역', { exact: true })).toHaveCount(0);
  // 연속 작업 시 이전 안내는 최신으로 교체된다(누적 없음) — 성공 토스트는 하나만 남는다.
  await expect(toast).toHaveCount(1);
  expect(operations[3]).toEqual([
    {
      action: 'REJECT',
      scope: 'SCENE',
      tagType: 'location',
      matchValue: '서울역',
      displayName: '서울역',
    },
  ]);
});

test('장면 제외 후보는 한 버튼에서 등록하고 취소한다', async ({ page }) => {
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.resolution = 'correction';
  current.history.reviewerLoginId = 'e2e-reviewer';
  current.history.reviewerName = 'E2E 검수자';
  const methods: string[] = [];
  const candidates = await mockCorrectionCandidates(page);

  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/scene-exclude-candidate', async (route) => {
    methods.push(route.request().method());
    if (route.request().method() === 'POST') {
      candidates.sceneExcludes = [{ searchRuleId: '61', targetSceneId: current.sceneId }];
      await success(route, { searchRuleId: '61', feedbackId: '41', active: false });
      return;
    }
    candidates.sceneExcludes = [];
    await success(route);
  });

  await page.goto('/review?inquiry=41');
  await page.getByRole('button', { name: '이 장면 제외', exact: true }).click();
  await expect(page.getByRole('button', { name: '제외 취소', exact: true })).toBeVisible();
  await expect(page.getByText('제외함', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '제외 취소', exact: true }).click();
  await expect(page.getByRole('button', { name: '이 장면 제외', exact: true })).toBeVisible();
  expect(methods).toEqual(['POST', 'DELETE']);
});

test('연결 실패는 같은 키로 재시도하고 요청 중 중복 입력을 막으며 서버 이력을 표시한다', async ({
  page,
}) => {
  await reviewer(page);
  let current = inquiry();
  const keys: (string | undefined)[] = [];
  let releaseClaim!: () => void;
  const pendingClaim = new Promise<void>((resolve) => {
    releaseClaim = resolve;
  });
  await mockList(page, () => [current]);
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/claim', async (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    if (keys.length === 1) return route.abort('failed');
    await pendingClaim;
    current = inquiry('41', 'REVIEWING');
    await success(route);
  });

  await page.goto('/review?view=inquiries');
  await page.getByRole('button', { name: /문의 #41/ }).click();
  await page.getByRole('button', { name: '검수 시작', exact: true }).click();
  await page.getByRole('button', { name: '검수 시작 다시 시도' }).click();
  await expect.poll(() => keys.length).toBe(2);
  await expect(page.getByRole('button', { name: '검수 시작 중…' })).toBeDisabled();
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
  releaseClaim();
  await expect(page.getByText(/서버 담당자/)).toBeVisible();
  await expect(page.getByRole('region', { name: '검수 시작 실패 안내' })).not.toBeVisible();
  await page.getByRole('button', { name: '문의 목록으로', exact: true }).click();
  await expect(page.getByRole('button', { name: /문의 #41/ })).toContainText('검수 중');
  expect(keys).toHaveLength(2);
});

test('최신 상세 조회가 실패하면 조회 오류를 안내하고 재조회 성공 후 이전 선점 오류가 남지 않는다', async ({
  page,
}) => {
  await reviewer(page);
  let reads = 0;
  await page.route('**/api/v1/review/inquiries/41', (route) => {
    reads++;
    if (reads === 2) return failure(route, 500, 'COMM_500');
    return success(route, inquiry('41', reads === 1 ? 'OPEN' : 'REVIEWING'));
  });
  await page.route('**/api/v1/review/inquiries/41/claim', (route) =>
    failure(route, 409, 'FEEDBACK_409_001'),
  );
  await page.goto('/review?inquiry=41');
  await page.getByRole('button', { name: '검수 시작', exact: true }).click();
  await page.getByRole('button', { name: '최신 상태 확인' }).click();
  await expect(page.getByRole('main').getByRole('alert')).toBeVisible();
  await expect(page.getByRole('main').getByRole('alert')).not.toContainText('COMM_500');
  await page.getByRole('button', { name: '다시 시도', exact: true }).click();
  await expect(page.getByText(/서버 담당자/)).toBeVisible();
  await expect(page.getByRole('region', { name: '검수 시작 실패 안내' })).not.toBeVisible();
});

for (const status of [403, 404]) {
  test(`선점 ${status} 오류는 재전송 없이 기존 목록 조건으로 복귀한다`, async ({ page }) => {
    await reviewer(page);
    let claims = 0;
    await mockList(page, () => [inquiry()]);
    await page.route('**/api/v1/review/inquiries/41', (route) => success(route, inquiry()));
    await page.route('**/api/v1/review/inquiries/41/claim', (route) => {
      claims++;
      return failure(route, status, `FEEDBACK_${status}_002`);
    });
    await page.goto('/review?status=open&inquiry=41');
    await page.getByRole('button', { name: '검수 시작', exact: true }).click();
    await page
      .getByRole('region', { name: '검수 시작 실패 안내' })
      .getByRole('button', { name: '문의 목록으로' })
      .click();
    await expect(page).toHaveURL(/\/review\?status=open$/);
    await expect(page.getByRole('button', { name: /문의 #41/ })).toBeVisible();
    expect(claims).toBe(1);
  });
}

test('페이지·상태 필터와 상세 복귀는 URL 조건과 브라우저 이력을 보존한다', async ({ page }) => {
  await reviewer(page);
  const items = Array.from({ length: 23 }, (_, index) => inquiry(String(41 + index)));
  items.push(inquiry('99', 'REVIEWING'));
  const requests = await mockList(page, () => items);
  await page.route('**/api/v1/review/inquiries/51', (route) => success(route, inquiry('51')));
  await page.goto('/review?keep=1');
  const pagination = page.getByRole('navigation', { name: '문의 목록 페이지' });
  await expect(page.getByRole('list', { name: '문의 목록' }).getByRole('listitem')).toHaveCount(10);
  await expect(pagination.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
  await pagination.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page).toHaveURL(/keep=1&page=2$/);
  await page.getByRole('button', { name: /문의 #51/ }).click();
  await page.getByRole('button', { name: '문의 목록으로', exact: true }).click();
  await expect(page).toHaveURL(/keep=1&page=2$/);
  await expect(page.getByRole('button', { name: /문의 #51/ })).toBeVisible();
  await pagination.getByRole('button', { name: '이전 페이지' }).click();
  await expect(page).toHaveURL(/\/review\?keep=1$/);
  await page.goBack();
  await expect(page).toHaveURL(/keep=1&page=2$/);
  await page
    .getByRole('group', { name: '문의 상태' })
    .getByRole('button', { name: /^검수 중/ })
    .click();
  await expect(page).toHaveURL(/keep=1&status=reviewing$/);
  await expect(page.getByRole('button', { name: /문의 #99/ })).toBeVisible();
  expect(requests.some((url) => url.searchParams.get('page') === '1')).toBe(true);
  expect(requests.at(-1)?.searchParams.get('status')).toBe('REVIEWING');
  expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
  await page.reload();
  await expect(page.getByRole('button', { name: /문의 #99/ })).toBeVisible();
});

test('번호 이동은 범위 밖 입력을 안내하고 목록 표시 개수는 URL 에 남기며 첫 페이지로 돌아간다', async ({
  page,
}) => {
  await reviewer(page);
  const requests = await mockList(page, () =>
    Array.from({ length: 45 }, (_, index) => inquiry(String(41 + index))),
  );
  await page.goto('/review?keep=1');
  const pagination = page.getByRole('navigation', { name: '문의 목록 페이지' });
  const pageInput = pagination.getByRole('textbox', { name: '이동할 페이지 번호' });
  await pageInput.fill('9');
  await pagination.getByRole('button', { name: '이동', exact: true }).click();
  await expect(pagination.getByRole('alert')).toHaveText('1~5 사이의 페이지 번호를 입력해 주세요.');
  await expect(pageInput).toHaveAttribute('aria-invalid', 'true');
  await expect(page).toHaveURL(/\/review\?keep=1$/);
  await pageInput.fill('4');
  await pageInput.press('Enter');
  await expect(page).toHaveURL(/keep=1&page=4$/);
  await expect(pagination.getByRole('button', { name: '4페이지' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await page.getByRole('combobox', { name: '목록 표시 개수' }).selectOption('20');
  await expect(page).toHaveURL(/keep=1&size=20$/);
  await expect(page.getByRole('list', { name: '문의 목록' }).getByRole('listitem')).toHaveCount(20);
  expect(requests.at(-1)?.searchParams.get('size')).toBe('20');
  expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
  await expect(pagination.getByRole('button', { name: '3페이지' })).toBeVisible();
  await expect(pagination.getByRole('button', { name: '4페이지' })).toHaveCount(0);
});

test('허용하지 않는 목록 표시 개수는 URL 에서 걷어 낸다', async ({ page }) => {
  await reviewer(page);
  await mockList(page, () => Array.from({ length: 45 }, (_, index) => inquiry(String(41 + index))));
  await page.goto('/review?size=30&keep=1');
  await expect(page).toHaveURL(/\/review\?keep=1$/);
  await expect(page.getByRole('combobox', { name: '목록 표시 개수' })).toHaveValue('10');
});

test('범위 초과 페이지는 마지막 페이지로 보정하고 빈 목록은 첫 페이지로 복귀한다', async ({
  page,
}) => {
  await reviewer(page);
  await mockList(page, () => Array.from({ length: 23 }, (_, index) => inquiry(String(41 + index))));
  await page.goto('/review?status=open&page=99&keep=1');
  await expect(page).toHaveURL(/status=open&page=3&keep=1$/);
  await expect(page.getByRole('list', { name: '문의 목록' }).getByRole('listitem')).toHaveCount(3);
  await expect(page.getByRole('button', { name: '다음 페이지', exact: true })).toBeDisabled();
  await page.goto('/review?status=closed&page=99&keep=1');
  await expect(page).toHaveURL(/status=closed&keep=1$/);
  await expect(
    page.getByRole('heading', { name: '해당 상태 문의 없음', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '이전 페이지', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '다음 페이지', exact: true })).toBeDisabled();
});

for (const snapshot of [
  null,
  'not-json',
  '{}',
  JSON.stringify({
    date_windows: [],
    incident_names: [],
    entities: [],
    locations: [{ value: '서울역' }],
    expanded_terms: [],
  }),
]) {
  test(`검색 해석 snapshot ${snapshot}은 안전한 요약 또는 안내로 표시한다`, async ({ page }) => {
    await reviewer(page);
    const current = inquiry();
    current.execution.parsedQueryJson = snapshot;
    const errors: Error[] = [];
    page.on('pageerror', (error) => errors.push(error));
    await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
    await page.goto('/review?inquiry=41');
    const expected =
      snapshot === null
        ? '저장된 검색 해석이 없습니다.'
        : snapshot.includes('서울역')
          ? '서울역'
          : '저장된 검색 해석을 확인할 수 없습니다.';
    await expect(page.getByText(expected, { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '검수 시작', exact: true })).toBeVisible();
    expect(errors).toEqual([]);
  });
}

test('성공 안내는 자동 소멸하고 실패 안내는 남는다 (S15P21A501-303)', async ({ page }) => {
  await page.clock.install();
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.resolution = 'correction';
  current.history.reviewerLoginId = 'e2e-reviewer';
  let calls = 0;
  const candidates = await mockCorrectionCandidates(page);
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    calls += 1;
    // 첫 저장은 성공, 두 번째는 서버 실패 — 성공/실패 표시 정책을 한 흐름에서 확인한다.
    if (calls === 1) {
      addPendingTags(candidates, route.request().postDataJSON().operations, ['61']);
      await success(route, { feedbackId: '41', created: 1, evidenceIds: ['61'] });
    } else {
      await failure(route, 409, 'REVIEW_409_231');
    }
  });

  await page.goto('/review?inquiry=41');
  const tagType = page.getByRole('combobox', { name: '태그 유형', exact: true });
  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await tagType.selectOption('keyword');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('서울');
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();

  // 성공 안내는 토스트로 뜬다.
  const toast = page.getByText('태그 교정 후보를 저장했습니다. 검증과 확정 후 검색에 반영됩니다.');
  await expect(toast).toBeVisible();
  // 일정 시간(5초) 뒤 자동으로 사라져 레이아웃을 계속 차지하지 않는다.
  await page.clock.fastForward(5_100);
  await expect(toast).toHaveCount(0);

  // 두 번째 저장은 실패 — 실패 안내는 토스트가 아니라 작업 영역에 뜨고, 자동 소멸하지 않는다.
  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await tagType.selectOption('keyword');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('부산');
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();
  const error = page.getByText('요청을 처리할 수 없습니다.', { exact: false });
  await expect(error).toBeVisible();
  await page.clock.fastForward(10_000);
  await expect(error).toBeVisible();
  // 실패를 성공처럼 표시하지 않는다.
  await expect(page.getByText(/저장했습니다/)).toHaveCount(0);
});

test('판정 저장 성공도 자동 소멸 토스트로 뜬다 (S15P21A501-303)', async ({ page }) => {
  await page.clock.install();
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.history.reviewerLoginId = 'e2e-reviewer';
  const resolutions: unknown[] = [];
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/resolution', async (route) => {
    resolutions.push(route.request().postDataJSON());
    await success(route);
  });

  await page.goto('/review?inquiry=41');
  // 판정 전(resolution 없음) 문의에서 처리 결과를 교정으로 켜면 판정이 바로 저장된다.
  const toggle = page.getByRole('switch', { name: '처리 결과', exact: true });
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await toggle.click();
  const toast = page.getByText('판정을 저장했습니다.', { exact: false });
  await expect(toast).toBeVisible();
  expect(resolutions).toEqual([{ resolution: 'correction', note: null }]);
  await page.clock.fastForward(5_100);
  await expect(toast).toHaveCount(0);
});
