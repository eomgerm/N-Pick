import { expect, test, type Page, type Route } from '@playwright/test';

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
      source: string | null;
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
    await success(route, {
      items: filtered.slice(currentPage * 10, (currentPage + 1) * 10),
      page: currentPage,
      size: 10,
      totalElements: filtered.length,
      totalPages: Math.ceil(filtered.length / 10),
      statusCounts: {
        open: items.filter((item) => item.status === 'OPEN').length,
        reviewing: items.filter((item) => item.status === 'REVIEWING').length,
        closed: items.filter((item) => item.status === 'CLOSED').length,
      },
    });
  });
  return requests;
}

// 검수 중 문의는 문의 화면의 status 필터가 담당한다. 처리 현황 화면에는 문의 탭이 없다.
test('검수 중 문의는 실제 목록·상세를 조회하고 같은 필터로 복귀한다', async ({ page }) => {
  await reviewer(page);
  const requests = await mockList(page, () => [inquiry('41', 'REVIEWING')]);
  await page.route('**/api/v1/review/inquiries/41', (route) =>
    success(route, inquiry('41', 'REVIEWING')),
  );
  await page.goto('/review?status=reviewing');
  await expect(page.getByRole('button', { name: /문의 #41/ })).toBeVisible();
  await page.getByRole('button', { name: /문의 #41/ }).click();
  await expect(page.getByText(/서버 담당자/)).toBeVisible();
  await page.getByRole('button', { name: '문의 목록으로', exact: true }).click();
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
  await expect(page.getByText('이 상태의 문의가 없습니다.')).toBeVisible();
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

test('담당 검수자는 쉼표로 여러 태그를 추가하고 기존 태그 삭제 후보를 만든다', async ({ page }) => {
  await reviewer(page);
  const current = inquiry('41', 'REVIEWING');
  current.resolution = 'tag_correction';
  current.history.reviewerLoginId = 'e2e-reviewer';
  current.history.reviewerName = 'E2E 검수자';
  current.evidence = [
    {
      taggingId: '51',
      tagType: 'location',
      matchValue: '서울역',
      tagName: '서울역',
      source: 'ocr',
      verifiedState: 'verified',
      scope: 'SCENE',
    },
  ];
  const operations: unknown[][] = [];
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current));
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    const body = route.request().postDataJSON() as { operations: unknown[] };
    operations.push(body.operations);
    await success(route, {
      feedbackId: '41',
      created: body.operations.length,
      evidenceIds: body.operations.map((_, index) => String(61 + index)),
    });
  });

  await page.goto('/review?inquiry=41');
  await page.getByRole('textbox', { name: '태그 값 (쉼표로 구분)' }).fill('서울, 부산, 서울, 광주');
  await expect(page.getByText('3개 후보: 서울 · 부산 · 광주')).toBeVisible();
  await page.getByRole('button', { name: '3개 추가 후보 만들기' }).click();
  await expect(page.getByText(/3개 태그를 검증 후보로 저장했습니다/)).toBeVisible();
  expect(operations[0]).toEqual([
    {
      action: 'APPROVE',
      scope: 'SCENE',
      tagType: 'keyword',
      matchValue: '서울',
      displayName: '서울',
    },
    {
      action: 'APPROVE',
      scope: 'SCENE',
      tagType: 'keyword',
      matchValue: '부산',
      displayName: '부산',
    },
    {
      action: 'APPROVE',
      scope: 'SCENE',
      tagType: 'keyword',
      matchValue: '광주',
      displayName: '광주',
    },
  ]);

  await page.getByRole('button', { name: '삭제 후보' }).click();
  await expect(page.getByText(/서울역.*태그를 삭제 후보로 만들까요/)).toBeVisible();
  await page.getByRole('button', { name: '삭제 후보 저장' }).click();
  await expect(page.getByText(/'서울역' 삭제 후보를 저장했습니다/)).toBeVisible();
  expect(operations[1]).toEqual([
    {
      action: 'REJECT',
      scope: 'SCENE',
      tagType: 'location',
      matchValue: '서울역',
      displayName: '서울역',
    },
  ]);
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
  await expect(pagination.getByRole('button', { name: '이전' })).toBeDisabled();
  await pagination.getByRole('button', { name: '다음' }).click();
  await expect(page).toHaveURL(/keep=1&page=2$/);
  await page.getByRole('button', { name: /문의 #51/ }).click();
  await page.getByRole('button', { name: '문의 목록으로', exact: true }).click();
  await expect(page).toHaveURL(/keep=1&page=2$/);
  await expect(page.getByRole('button', { name: /문의 #51/ })).toBeVisible();
  await pagination.getByRole('button', { name: '이전' }).click();
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

test('범위 초과 페이지는 마지막 페이지로 보정하고 빈 목록은 첫 페이지로 복귀한다', async ({
  page,
}) => {
  await reviewer(page);
  await mockList(page, () => Array.from({ length: 23 }, (_, index) => inquiry(String(41 + index))));
  await page.goto('/review?status=open&page=99&keep=1');
  await expect(page).toHaveURL(/status=open&page=3&keep=1$/);
  await expect(page.getByRole('list', { name: '문의 목록' }).getByRole('listitem')).toHaveCount(3);
  await expect(page.getByRole('button', { name: '다음', exact: true })).toBeDisabled();
  await page.goto('/review?status=closed&page=99&keep=1');
  await expect(page).toHaveURL(/status=closed&keep=1$/);
  await expect(page.getByText('이 상태의 문의가 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: '이전', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '다음', exact: true })).toBeDisabled();
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
