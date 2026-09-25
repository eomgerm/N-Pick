import { expect, test, type Page, type Route } from '@playwright/test';

// 교정 토글이 켜진(correction) 검수 중 문의만 장면 제외·해석 교정 편집을 연다.
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
      // 해석 칩 편집기는 당시 해석 스냅샷에서 칩을 씨딩한다 — 빈 객체면 편집할 칩이 없다.
      parsedQueryJson: JSON.stringify({
        date_windows: [],
        incident_names: [],
        entities: [],
        locations: [{ value: '서울역', type: 'location', origin: 'explicit' }],
        expanded_terms: ['귀성길'],
      }),
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

async function openInquiry(page: Page) {
  await page.clock.install();
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, inquiry()));
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
  await openInquiry(page);

  const save = page.getByRole('button', { name: '이 장면 제외', exact: true });
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

// 해석 칩 편집기는 담기 성공을 토스트가 아니라 편집기 안 상태 문구로 남긴다 — 담은 교정이
// '바뀌는 점'과 함께 계속 보여야 아래 검증으로 이어 갈 수 있기 때문이다 (S15P21A501-281).
test('해석 교정 실패 안내는 남고 재시도 성공은 오류를 지우고 담기 완료를 안내한다 (S15P21A501-303)', async ({
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
  await openInquiry(page);

  await expect(page.getByRole('heading', { name: '검색 해석 교정', exact: true })).toBeVisible();
  await page.getByRole('button', { name: "'귀성길' 삭제", exact: true }).click();

  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await save.click();
  const error = page.getByText('검수 중인 문의만 규칙 후보를 저장할 수 있습니다.');
  await expect(error).toBeVisible();
  await page.clock.fastForward(10_000);
  await expect(error).toBeVisible();

  await save.click();
  await expect(page.getByText('1개 교정을 담았어요. 아래에서 검증하고 확정하세요.')).toBeVisible();
  await expect(error).toHaveCount(0);
  expect(calls).toBe(2);
});
