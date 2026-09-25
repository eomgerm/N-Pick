import { expect, test, type Page, type Route } from '@playwright/test';

// 검수 취소(선점 해제) 흐름 (S15P21A501-289).
type InquiryState = 'reviewing' | 'open';

function inquiry(state: InquiryState, reviewerLoginId = 'e2e-reviewer') {
  const reviewing = state === 'reviewing';
  return {
    feedbackId: '41',
    status: reviewing ? 'REVIEWING' : 'OPEN',
    resolution: reviewing ? 'correction' : null,
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
      parsedQueryJson: null,
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
    history: reviewing
      ? {
          reviewedById: '2',
          reviewerName: 'E2E 검수자',
          reviewerLoginId,
          reviewStartedAt: '2026-09-09T02:00:00Z',
          verifiedByExecutionId: null,
        }
      : {
          reviewedById: null,
          reviewerName: null,
          reviewerLoginId: null,
          reviewStartedAt: null,
          verifiedByExecutionId: null,
        },
  };
}

async function success(route: Route, data?: unknown) {
  await route.fulfill({
    json: { isSuccess: true, code: 'COMM_200', message: '요청에 성공했습니다.', data },
  });
}

async function openInquiry(page: Page, current: () => ReturnType<typeof inquiry>) {
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, current()));
  await page.goto('/review?inquiry=41');
}

const releaseButton = (page: Page) =>
  page.getByRole('region', { name: '검수 취소' }).getByRole('button', { name: '검수 취소' });

test('검수 취소는 검수 중인 담당자 본인에게만 보인다', async ({ page }) => {
  let current = inquiry('reviewing', 'other-reviewer');
  await openInquiry(page, () => current);
  await expect(page.getByText('다른 아카이브 팀이 처리 중입니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: '검수 취소' })).toHaveCount(0);

  current = inquiry('open');
  await page.reload();
  await expect(page.getByRole('button', { name: /검수 시작/ })).toBeVisible();
  await expect(page.getByRole('button', { name: '검수 취소' })).toHaveCount(0);

  current = inquiry('reviewing');
  await page.reload();
  await expect(releaseButton(page)).toBeVisible();
});

test('확인하면 DELETE 를 보내고 성공 안내 뒤 미담당 상태로 돌아간다', async ({ page }) => {
  let current = inquiry('reviewing');
  const requests: string[] = [];
  await page.route('**/api/v1/review/inquiries/41/claim', async (route) => {
    requests.push(route.request().method());
    current = inquiry('open');
    await success(route, null);
  });
  await openInquiry(page, () => current);

  // 돌아가기는 요청 없이 닫힌다.
  await releaseButton(page).click();
  const dialog = page.getByRole('dialog', { name: '검수를 취소할까요?' });
  await expect(dialog).toContainText(
    '검수를 취소하면 작성한 교정 후보가 모두 폐기되고, 다른 검수자가 이 문의를 맡을 수 있습니다.',
  );
  await dialog.getByRole('button', { name: '돌아가기' }).click();
  await expect(dialog).toHaveCount(0);
  await releaseButton(page).click();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  expect(requests).toEqual([]);

  await releaseButton(page).click();
  await dialog.getByRole('button', { name: '검수 취소' }).click();

  await expect(page.getByRole('status').filter({ hasText: '검수를 취소했습니다.' })).toBeVisible();
  await expect(page.getByText('아직 담당자가 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: /검수 시작/ })).toBeEnabled();
  await expect(page.getByRole('button', { name: '검수 취소' })).toHaveCount(0);
  await expect(page.getByText('처리 판정')).toHaveCount(0);
  expect(requests).toEqual(['DELETE']);
});

test('확인창은 Tab 을 안에 가두고 닫으면 검수 취소 버튼으로 포커스를 돌려준다', async ({
  page,
}) => {
  await openInquiry(page, () => inquiry('reviewing'));
  const dialog = page.getByRole('dialog', { name: '검수를 취소할까요?' });
  const back = dialog.getByRole('button', { name: '돌아가기' });
  const confirm = dialog.getByRole('button', { name: '검수 취소' });

  await releaseButton(page).click();
  await expect(back).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(confirm).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(back).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(confirm).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(back).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(releaseButton(page)).toBeFocused();

  await releaseButton(page).click();
  await back.click();
  await expect(dialog).toHaveCount(0);
  await expect(releaseButton(page)).toBeFocused();
});

async function confirmRelease(page: Page) {
  await releaseButton(page).click();
  await page
    .getByRole('dialog', { name: '검수를 취소할까요?' })
    .getByRole('button', { name: '검수 취소' })
    .click();
}

test('보안 계층 403 은 담당자 안내 대신 서버 문구를 보여 주고 검수 중 상태를 유지한다', async ({
  page,
}) => {
  await page.route('**/api/v1/review/inquiries/41/claim', (route) =>
    route.fulfill({
      status: 403,
      json: { isSuccess: false, code: 'COMM_403', message: 'Access is denied' },
    }),
  );
  await openInquiry(page, () => inquiry('reviewing'));

  await confirmRelease(page);

  await expect(
    page.getByRole('alert').filter({ hasText: '권한이 없거나 요청 보안 정보가 만료되었습니다.' }),
  ).toBeVisible();
  await expect(page.getByText('이 문의의 담당자만 검수를 취소할 수 있습니다.')).toHaveCount(0);
  await expect(releaseButton(page)).toBeEnabled();
  await expect(page.getByText('처리 판정')).toBeVisible();
  await expect(page.getByText('검수를 취소했습니다.')).toHaveCount(0);
});

test('담당자가 아니라는 403 은 담당자 안내를 보여 준다', async ({ page }) => {
  await page.route('**/api/v1/review/inquiries/41/claim', (route) =>
    route.fulfill({
      status: 403,
      json: { isSuccess: false, code: 'FEEDBACK_403_002', message: 'Not assigned reviewer' },
    }),
  );
  await openInquiry(page, () => inquiry('reviewing'));

  await confirmRelease(page);

  await expect(
    page.getByRole('alert').filter({ hasText: '이 문의의 담당자만 검수를 취소할 수 있습니다.' }),
  ).toBeVisible();
  await expect(releaseButton(page)).toBeEnabled();
});

test('이미 풀린 문의의 409 뒤에는 상세를 다시 불러와 미담당 상태로 맞춘다', async ({ page }) => {
  // 이전 요청의 응답을 잃은 경우처럼 서버에서는 이미 풀렸고, 재요청은 409 를 받는다.
  let current = inquiry('reviewing');
  let detailGets = 0;
  page.on('request', (request) => {
    if (request.method() === 'GET' && request.url().endsWith('/api/v1/review/inquiries/41')) {
      detailGets += 1;
    }
  });
  await page.route('**/api/v1/review/inquiries/41/claim', async (route) => {
    current = inquiry('open');
    await route.fulfill({
      status: 409,
      json: { isSuccess: false, code: 'FEEDBACK_409_003', message: 'Not reviewing' },
    });
  });
  await openInquiry(page, () => current);
  await expect(releaseButton(page)).toBeVisible();
  const getsBefore = detailGets;

  await confirmRelease(page);

  await expect(page.getByText('아직 담당자가 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: /검수 시작/ })).toBeEnabled();
  await expect(page.getByRole('button', { name: '검수 취소' })).toHaveCount(0);
  expect(detailGets).toBeGreaterThan(getsBefore);
  await expect(page.getByText('검수를 취소했습니다.')).toHaveCount(0);
});

test('태그 후보 변경이 진행 중이면 검수 취소를 잠근다', async ({ page }) => {
  let releasePost: () => void = () => {};
  const postHeld = new Promise<void>((resolve) => {
    releasePost = resolve;
  });
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    await postHeld;
    await success(route, { feedbackId: '41', created: 1, evidenceIds: ['71'] });
  });
  await openInquiry(page, () => inquiry('reviewing'));

  await expect(releaseButton(page)).toBeEnabled();
  await page.getByRole('button', { name: '‘서울역’ 삭제 후보' }).click();
  await expect(releaseButton(page)).toBeDisabled();

  releasePost();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();
  await expect(releaseButton(page)).toBeEnabled();
});
