import { expect, test, type Page, type Route } from '@playwright/test';

function resolution(overrides: Record<string, unknown> = {}) {
  return JSON.stringify({
    schema_version: 'resolution-v1',
    intent: 'scene_search',
    date_windows: [],
    incident_names: [],
    entities: [],
    locations: [],
    expanded_terms: [],
    confidence: 0.72,
    ...overrides,
  });
}

function inquiry(parsedQueryJson: string) {
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
    comment: '검색 의미가 빠졌습니다.',
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
    evidence: [],
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

async function openInquiry(page: Page, parsedQueryJson: string) {
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) =>
    success(route, inquiry(parsedQueryJson)),
  );
  await page.goto('/review?inquiry=41');
}

test('명시값이 없으면 원본 해석값을 고른 뒤 새 항목을 추가한다 (S15P21A501-323)', async ({
  page,
}) => {
  const bodies: Array<{ condition: { all: unknown[] } }> = [];
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    if (route.request().method() !== 'POST') return success(route);
    bodies.push(route.request().postDataJSON());
    await success(route, { searchRuleId: '81', feedbackId: '41', active: false });
  });
  await openInquiry(
    page,
    resolution({
      entities: [
        {
          type: 'organization',
          value: '한국도로공사',
          origin: 'inferred',
          query_span: null,
          confidence: 0.72,
        },
      ],
      expanded_terms: ['교통 정체'],
    }),
  );

  const addTerm = page.getByRole('button', { name: '검색 의미어에 항목 추가' });
  await expect(addTerm).toBeEnabled();
  await expect(page.getByText('새 항목을 추가하려면 적용 기준을 선택해 주세요.')).toBeVisible();

  await addTerm.click();
  await expect(page.getByRole('heading', { name: '어떤 해석에 추가할까요?' })).toBeVisible();
  await expect(page.getByText('AI가 추론한 값은 다음 검색에서 달라질 수 있습니다.')).toBeVisible();
  await page
    .getByRole('button', { name: '인물·기관 ‘한국도로공사’ AI 추론값을 적용 기준으로 선택' })
    .click();

  const input = page.getByRole('textbox', { name: '검색 의미어 값 수정' });
  await input.fill('교통량');
  await input.press('Enter');
  await expect(
    page.getByText('인물·기관 ‘한국도로공사’가 있을 때만 새 항목을 추가합니다.'),
  ).toBeVisible();

  await page.getByRole('button', { name: '교정 담기', exact: true }).click();
  await expect(page.getByText('1개 교정을 담았어요.', { exact: false })).toBeVisible();
  expect(bodies).toHaveLength(1);
  expect(bodies[0].condition.all).toEqual([
    { axis: 'entities', op: 'has_value', value: '한국도로공사' },
  ]);
});

test('적용 기준 후보가 없으면 다른 검색에 미칠 영향과 제한 이유를 설명한다 (S15P21A501-323)', async ({
  page,
}) => {
  await openInquiry(page, resolution());

  await expect(
    page.getByText(
      '새 항목은 같은 해석을 가진 이후 검색에도 적용됩니다. 현재 해석에는 적용 범위를 정할 기준이 없어 추가할 수 없습니다.',
    ),
  ).toBeVisible();
  await expect(page.getByText('기존 항목의 수정·이동·삭제는 계속할 수 있습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: '검색 의미어에 항목 추가' })).toBeDisabled();
});

test('적용 기준 값이 100자를 넘으면 저장 전에 길이 제한을 설명한다 (S15P21A501-323)', async ({
  page,
}) => {
  await openInquiry(page, resolution({ expanded_terms: ['가'.repeat(101)] }));

  await expect(
    page.getByText('원본 해석값이 적용 기준의 100자 제한을 넘어 새 항목을 추가할 수 없습니다.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '검색 의미어에 항목 추가' })).toBeDisabled();
});

test('조건 선택 중 교정이 10개가 되면 빈 칩을 추가하지 않는다 (S15P21A501-323)', async ({
  page,
}) => {
  const terms = Array.from({ length: 10 }, (_, index) => `기준${index + 1}`);
  await openInquiry(page, resolution({ expanded_terms: terms }));

  for (const term of terms.slice(0, 9)) {
    await page.getByRole('button', { name: `'${term}' 삭제`, exact: true }).click();
  }
  const addLocation = page.getByRole('button', { name: '장소·시설에 항목 추가' });
  await expect(addLocation).toBeEnabled();
  await addLocation.click();
  await expect(page.getByRole('heading', { name: '어떤 해석에 추가할까요?' })).toBeVisible();

  await page.getByRole('button', { name: `'기준10' 삭제`, exact: true }).click();
  await expect(page.getByText('10/10', { exact: false })).toBeVisible();
  await page
    .getByRole('button', { name: '검색 의미어 ‘기준1’ AI 추론값을 적용 기준으로 선택' })
    .click();

  await expect(page.getByRole('textbox', { name: '장소·시설 값 수정' })).toHaveCount(0);
  await expect(page.getByText('10/10', { exact: false })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '모두 채웠습니다' })).toBeVisible();
});
