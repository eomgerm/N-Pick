import { expect, test, type Page, type Route } from '@playwright/test';

function inquiry(resolution: 'exclude_scene' | 'patch_parse') {
  return {
    feedbackId: '41',
    status: 'REVIEWING',
    resolution,
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
      parsedQueryJson: '{}',
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

async function failure(route: Route, code: string) {
  await route.fulfill({
    status: 409,
    json: { isSuccess: false, code, message: '요청을 처리할 수 없습니다.' },
  });
}

async function openInquiry(page: Page, resolution: 'exclude_scene' | 'patch_parse') {
  await page.clock.install();
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, inquiry(resolution)));
  await page.goto('/review?inquiry=41');
}

test('장면 제외 실패 후 재시도 성공은 토스트로 뜨고 자동 소멸한다 (S15P21A501-303)', async ({
  page,
}) => {
  let calls = 0;
  await page.route('**/api/v1/review/inquiries/41/scene-exclude-candidate', async (route) => {
    calls += 1;
    if (calls === 1) {
      await failure(route, 'SRCH_409_211');
      return;
    }
    await success(route, { searchRuleId: '71', feedbackId: '41', active: false });
  });
  await openInquiry(page, 'exclude_scene');

  const save = page.getByRole('button', { name: '제외 후보 저장', exact: true });
  await save.click();
  const error = page.getByText('검수 중인 문의가 아닙니다. 최신 상태를 다시 확인해 주세요.');
  await expect(error).toBeVisible();
  await page.clock.fastForward(10_000);
  await expect(error).toBeVisible();

  await save.click();
  const toast = page.getByText('제외 후보를 저장했습니다.', { exact: false });
  await expect(toast).toBeVisible();
  await expect(error).toHaveCount(0);
  await page.clock.fastForward(5_100);
  await expect(toast).toHaveCount(0);
  expect(calls).toBe(2);
});

test('해석 교정 실패 후 재시도 성공은 토스트로 뜨고 자동 소멸한다 (S15P21A501-303)', async ({
  page,
}) => {
  let calls = 0;
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    calls += 1;
    if (calls === 1) {
      await failure(route, 'SRCH_409_201');
      return;
    }
    await success(route, { searchRuleId: '72', feedbackId: '41', active: false });
  });
  await openInquiry(page, 'patch_parse');

  const conditions = page.getByRole('group', { name: '적용 조건' });
  await conditions.getByRole('textbox', { name: '값 (해당 조건만)' }).fill('서울역');
  const changes = page.getByRole('group', { name: '변경 내용' });
  await changes.getByRole('combobox', { name: '해석 항목' }).selectOption('expanded_terms');
  await changes.getByRole('textbox', { name: '값', exact: true }).fill('귀성길');

  const save = page.getByRole('button', { name: '해석 교정 후보 저장', exact: true });
  await save.click();
  const error = page.getByText('검수 중인 문의만 규칙 후보를 저장할 수 있습니다.');
  await expect(error).toBeVisible();
  await page.clock.fastForward(10_000);
  await expect(error).toBeVisible();

  await save.click();
  const toast = page.getByText('해석 교정 후보를 저장했습니다.', { exact: false });
  await expect(toast).toBeVisible();
  await expect(error).toHaveCount(0);
  await page.clock.fastForward(5_100);
  await expect(toast).toHaveCount(0);
  expect(calls).toBe(2);
});
