import { expect, test, type Page, type Route } from '@playwright/test';

const parsedQueryJson = JSON.stringify({
  schema_version: 'resolution-v1',
  intent: 'scene_search',
  date_windows: [],
  incident_names: [{ value: '추석', origin: 'explicit_query', query_span: null, confidence: 1 }],
  entities: [],
  locations: [],
  expanded_terms: ['귀성 차량', '고속도로 정체'],
  confidence: 0.92,
});

function inquiry() {
  return {
    feedbackId: '41',
    status: 'REVIEWING',
    resolution: 'correction',
    resolutionNote: null,
    createdAt: '2026-09-09T01:00:00Z',
    queryText: '귀성길 정체',
    sceneId: '31',
    scene: {
      sceneId: '31',
      clipId: '21',
      clipTitle: '저녁 뉴스',
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
      parsedQueryJson,
      resolverOutputJson: null,
      appliedRulesJson: '[]',
      appliedExcludesJson: '[]',
    },
    evidence: [
      {
        taggingId: '51',
        tagType: 'location',
        matchValue: '서울역',
        tagName: '서울역',
        sources: ['ocr'],
        verifiedState: 'VERIFIED',
        scope: 'SCENE',
      },
    ],
    history: {
      reviewedById: '2',
      reviewerName: 'E2E 검수자',
      reviewerLoginId: 'e2e-reviewer',
      reviewStartedAt: '2026-09-09T02:00:00Z',
      verifiedByExecutionId: null,
    },
  };
}

async function success(route: Route, data?: unknown) {
  await route.fulfill({
    json: { isSuccess: true, code: 'COMM_200', message: '요청에 성공했습니다.', data },
  });
}

async function openInquiry(page: Page) {
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, inquiry()));
  await page.goto('/review?inquiry=41');
}

async function addSceneTag(page: Page, value: string) {
  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill(value);
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();
  await expect(page.getByRole('button', { name: `‘${value}’ 추가 취소` })).toBeVisible();
}

test('태그 추가 취소는 그 추가의 근거만 지우고 다른 후보는 남긴다 (S15P21A501-309)', async ({
  page,
}) => {
  const posts: unknown[] = [];
  const deletes: string[] = [];
  let nextEvidenceId = 61;
  let failNextDelete = true;
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    if (route.request().method() !== 'POST') {
      deletes.push(`bulk:${route.request().method()}`);
      await success(route);
      return;
    }
    posts.push(route.request().postDataJSON());
    await success(route, {
      feedbackId: '41',
      created: 1,
      evidenceIds: [String(nextEvidenceId++)],
    });
  });
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate/*', async (route) => {
    deletes.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
    if (failNextDelete) {
      failNextDelete = false;
      await route.fulfill({
        status: 409,
        json: { isSuccess: false, code: 'TAG_409_999', message: '요청을 처리할 수 없습니다.' },
      });
      return;
    }
    await success(route);
  });
  await openInquiry(page);

  await addSceneTag(page, '서울');
  await addSceneTag(page, '부산');

  // 첫 취소는 실패한다 — 오류를 보여 주고 칩은 그대로 남는다.
  const cancelSeoul = page.getByRole('button', { name: '‘서울’ 추가 취소' });
  await cancelSeoul.click();
  await expect(
    page.getByRole('alert').filter({ hasText: '요청을 처리할 수 없습니다' }),
  ).toBeVisible();
  await expect(cancelSeoul).toBeVisible();

  // 다시 누르면 같은 근거만 지우고, 다시 올리는 POST 없이 칩이 사라진다.
  await cancelSeoul.click();
  await expect(cancelSeoul).toHaveCount(0);
  await expect(page.getByRole('button', { name: '‘부산’ 추가 취소' })).toBeVisible();
  expect(deletes).toEqual([
    'DELETE /api/v1/review/inquiries/41/tag-correction-candidate/61',
    'DELETE /api/v1/review/inquiries/41/tag-correction-candidate/61',
  ]);
  expect(posts).toHaveLength(2);
});

test('삭제 취소는 REJECT 근거만 지우고, 지우는 동안 태그 조작과 검증을 잠근다 (S15P21A501-309)', async ({
  page,
}) => {
  const posts: Array<{ operations: Array<{ action: string }> }> = [];
  const deletes: string[] = [];
  let releaseDelete: () => void = () => {};
  const deleteHeld = new Promise<void>((resolve) => {
    releaseDelete = resolve;
  });
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    posts.push(route.request().postDataJSON());
    await success(route, { feedbackId: '41', created: 1, evidenceIds: ['71'] });
  });
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate/*', async (route) => {
    deletes.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
    await deleteHeld;
    await success(route);
  });
  await openInquiry(page);

  await page.getByRole('button', { name: '‘서울역’ 삭제 후보' }).click();
  const restore = page.getByRole('button', { name: '‘서울역’ 삭제 취소' });
  await expect(restore).toBeVisible();
  expect(posts.map((body) => body.operations.map((operation) => operation.action))).toEqual([
    ['REJECT'],
  ]);

  await restore.click();
  await expect(restore).toBeDisabled();
  await expect(page.getByRole('button', { name: '+ 이 장면', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '+ 영상 전체', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '후보 검증', exact: true })).toBeDisabled();

  releaseDelete();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 후보' })).toBeVisible();
  await expect(restore).toHaveCount(0);
  await expect(page.getByRole('button', { name: '후보 검증', exact: true })).toBeEnabled();
  expect(deletes).toEqual(['DELETE /api/v1/review/inquiries/41/tag-correction-candidate/71']);
  expect(posts).toHaveLength(1);
});

test('해석 교정 저장 중에는 칩 편집이 잠긴다 (S15P21A501-309)', async ({ page }) => {
  let releaseSave: () => void = () => {};
  const saveHeld = new Promise<void>((resolve) => {
    releaseSave = resolve;
  });
  const bodies: unknown[] = [];
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    if (route.request().method() !== 'POST') {
      await success(route);
      return;
    }
    bodies.push(route.request().postDataJSON());
    await saveHeld;
    await success(route, { searchRuleId: '72', feedbackId: '41', active: false });
  });
  await openInquiry(page);

  await page.getByRole('button', { name: "'귀성 차량' 삭제", exact: true }).click();
  await page.getByRole('button', { name: '교정 담기', exact: true }).click();
  await expect(page.getByRole('button', { name: '담는 중…', exact: true })).toBeDisabled();

  const remaining = page.getByRole('button', { name: "'고속도로 정체' 삭제", exact: true });
  await expect(remaining).toBeDisabled();
  await expect(page.getByRole('button', { name: '검색 의미어에 항목 추가' })).toBeDisabled();
  const chip = page.getByRole('button', { name: /^고속도로 정체/ }).first();
  await expect(chip).toHaveAttribute('aria-disabled', 'true');
  await expect(chip).toHaveAttribute('draggable', 'false');
  await chip.click({ force: true });
  await expect(page.getByRole('textbox', { name: /값 수정$/ })).toHaveCount(0);

  releaseSave();
  await expect(page.getByText('1개 교정을 담았어요.', { exact: false })).toBeVisible();
  await expect(remaining).toBeEnabled();
  expect(bodies).toHaveLength(1);
});

interface ParseCall {
  method: string;
  key: string | null;
  all: unknown[];
}

// 멱등성 키별로 후보를 기억하는 목 서버. 같은 키 재요청은 기존 후보를 돌려준다(200 재생).
async function mockParseCandidates(
  page: Page,
  failOnce: (call: ParseCall) => boolean = () => false,
) {
  const calls: ParseCall[] = [];
  const created = new Map<string, string>();
  let failed = false;
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    const request = route.request();
    if (request.method() !== 'POST') {
      calls.push({ method: request.method(), key: null, all: [] });
      created.clear();
      await success(route);
      return;
    }
    const body = request.postDataJSON() as { condition: { all: unknown[] } };
    const key = (await request.headerValue('idempotency-key')) ?? '';
    const call = { method: 'POST', key, all: body.condition.all };
    calls.push(call);
    if (!failed && !created.has(key) && failOnce(call)) {
      failed = true;
      await route.fulfill({
        status: 409,
        json: { isSuccess: false, code: 'SRCH_409_201', message: '요청을 처리할 수 없습니다.' },
      });
      return;
    }
    const searchRuleId = created.get(key) ?? String(80 + created.size);
    created.set(key, searchRuleId);
    await success(route, { searchRuleId, feedbackId: '41', active: false });
  });
  return { calls, created };
}

test('서로 다른 축의 두 편집은 각자 조건 1개인 후보 2건으로 저장된다 (S15P21A501-309)', async ({
  page,
}) => {
  const { calls, created } = await mockParseCandidates(page);
  await openInquiry(page);

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  await page.getByRole('button', { name: "'귀성 차량' 삭제", exact: true }).click();
  await page.getByRole('button', { name: '교정 담기', exact: true }).click();
  await expect(page.getByText('2개 교정을 담았어요.', { exact: false })).toBeVisible();

  expect(calls.map((call) => call.all)).toEqual([
    [{ axis: 'incident_names', op: 'has_value', value: '추석' }],
    [{ axis: 'expanded_terms', op: 'has_value', value: '귀성 차량' }],
  ]);
  expect(new Set(calls.map((call) => call.key)).size).toBe(2);
  expect(created.size).toBe(2);
});

test('일부만 저장된 뒤 다시 담으면 같은 키로 재전송해 중복 후보가 생기지 않는다 (S15P21A501-309)', async ({
  page,
}) => {
  const { calls, created } = await mockParseCandidates(page, (call) =>
    JSON.stringify(call.all).includes('귀성 차량'),
  );
  await openInquiry(page);

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  await page.getByRole('button', { name: "'귀성 차량' 삭제", exact: true }).click();
  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await save.click();
  await expect(page.getByRole('alert').filter({ hasText: '검수 중인 문의만' })).toBeVisible();
  await expect(page.getByText('교정을 담았어요.', { exact: false })).toHaveCount(0);
  expect(created.size).toBe(1);

  await save.click();
  await expect(page.getByText('2개 교정을 담았어요.', { exact: false })).toBeVisible();
  expect(calls).toHaveLength(4);
  expect(calls[2].key).toBe(calls[0].key);
  expect(calls[3].key).toBe(calls[1].key);
  expect(created.size).toBe(2);
});

test('담은 뒤 다시 편집하면 이전 후보를 폐기하고 새 후보들을 저장한다 (S15P21A501-309)', async ({
  page,
}) => {
  const { calls } = await mockParseCandidates(page);
  await openInquiry(page);

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await save.click();
  await expect(page.getByText('1개 교정을 담았어요.', { exact: false })).toBeVisible();

  await page.getByRole('button', { name: "'귀성 차량' 삭제", exact: true }).click();
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.getByText('2개 교정을 담았어요.', { exact: false })).toBeVisible();

  expect(calls.map((call) => call.method)).toEqual(['POST', 'DELETE', 'POST', 'POST']);
  expect(calls.slice(2).map((call) => call.all)).toEqual([
    [{ axis: 'incident_names', op: 'has_value', value: '추석' }],
    [{ axis: 'expanded_terms', op: 'has_value', value: '귀성 차량' }],
  ]);
});
