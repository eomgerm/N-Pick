import { expect, test } from '@playwright/test';

const item = {
  feedback_id: '9007199254740993',
  search_execution_id: '100',
  search_result_id: '101',
  created_at: '2026-08-01T03:00:00Z',
  updated_at: '2026-09-17T03:00:00Z',
  query_text: '서버에 저장된 검색어',
  comment: '서버에 저장된 문의',
  status: 'OPEN',
  resolution: null,
  scene: {
    scene_id: '31',
    clip_id: '21',
    clip_title: '서버 영상 제목',
    start_time_ms: 1250,
    end_time_ms: 2500,
  },
};
const detail = {
  ...item,
  explicit_filters: {},
  resolution_note: null,
  review_started_at: null,
  closed_at: null,
  snapshot_status: 'unavailable',
  result_snapshot: null,
};
const success = (data: unknown) => ({ isSuccess: true, code: 'COMM_200', message: '성공', data });
const listPage = (items: (typeof item)[], page = 0, total = items.length) => ({
  items,
  page,
  size: 10,
  total_elements: total,
  total_pages: Math.ceil(total / 10),
  has_next: page + 1 < Math.ceil(total / 10),
});

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page
    .context()
    .addCookies([{ name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' }]);
});

test('문의 패널은 실제 목록·페이지와 상세의 최신 처리 결과를 읽는다', async ({
  page,
}, testInfo) => {
  const requests: string[] = [];
  const rows = Array.from({ length: 11 }, (_, index) => ({
    ...item,
    feedback_id: String(BigInt(item.feedback_id) + BigInt(index)),
    comment: `서버 문의 ${index + 1}`,
  }));
  await page.route('**/api/v1/inquiries?**', (route) => {
    requests.push(route.request().url());
    const current = Number(new URL(route.request().url()).searchParams.get('page'));
    return route.fulfill({
      json: success(listPage(rows.slice(current * 10, current * 10 + 10), current, 11)),
    });
  });
  let releaseDetail!: () => void;
  const detailReady = new Promise<void>((resolve) => {
    releaseDetail = resolve;
  });
  await page.route(`**/api/v1/inquiries/${item.feedback_id}`, async (route) => {
    await detailReady;
    return route.fulfill({
      json: success({
        ...detail,
        status: 'CLOSED',
        resolution: 'deferred',
        resolution_note: '담당자가 남긴 실제 처리 사유',
      }),
    });
  });
  await page.goto('/search');
  expect(requests).toHaveLength(0);
  await page.getByRole('button', { name: '문의 사항', exact: true }).click();
  const panel = page.getByRole('complementary', { name: '문의 사항', exact: true });
  await expect(panel.getByRole('listitem')).toHaveCount(10);
  expect(requests[0]).toContain('page=0&size=10');
  expect(requests[0]).not.toContain('member');
  await expect(panel.getByText('서버 영상 제목', { exact: true }).first()).toBeVisible();
  await expect(panel.getByText('2026. 8. 1.', { exact: true }).first()).toBeVisible();
  await panel.getByRole('button', { name: '다음 페이지' }).click();
  await expect(panel.getByRole('listitem')).toHaveCount(1);
  await expect(panel.getByText('서버 문의 11', { exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
  await panel.getByRole('button', { name: '이전 페이지' }).click();
  const row = panel.getByRole('button', { name: /^서버 문의 1 서버 영상 제목/ });
  await row.click();
  const dialog = page.getByRole('dialog', { name: '문의 상세', exact: true });
  await expect(dialog.getByRole('status')).toHaveText('문의 상세를 불러오는 중…');
  releaseDetail();
  await expect(dialog.getByText('담당자가 남긴 실제 처리 사유')).toBeVisible();
  await expect(dialog.getByText('처리 보류', { exact: true })).toBeVisible();
  await expect(dialog.getByText('서버에 저장된 검색어', { exact: true })).toBeVisible();
  await expect(
    dialog.getByText('당시 검색 결과의 상세 근거 기록은 제공되지 않습니다.'),
  ).toBeVisible();
  await expect(dialog.getByText(/화면 설명 일치|영상에서 확인한 내용 일치/)).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: testInfo.outputPath('my-inquiry-detail-mobile.png') });
  const bounds = (await dialog.boundingBox())!;
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(row).toBeFocused();
  await expect(panel).toBeVisible();
});

test('목록 실패는 오류·재시도를, 빈 목록은 실제 빈 상태를 표시한다', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/v1/inquiries?**', (route) =>
    ++attempts === 1
      ? route.fulfill({
          status: 500,
          json: { isSuccess: false, code: 'COMM_500', message: '문의 목록을 불러오지 못했습니다.' },
        })
      : route.fulfill({ json: success(listPage([])) }),
  );
  await page.goto('/search');
  await page.getByRole('button', { name: '문의 사항', exact: true }).click();
  const panel = page.getByRole('complementary', { name: '문의 사항', exact: true });
  await expect(panel.getByRole('alert')).toContainText('문의 목록을 불러오지 못했습니다.');
  await expect(panel.getByRole('listitem')).toHaveCount(0);
  await panel.getByRole('button', { name: '문의 목록 다시 시도' }).click();
  await expect(panel.getByText('접수한 문의가 없습니다.')).toBeVisible();
  await expect(panel.getByRole('alert')).toHaveCount(0);
  expect(attempts).toBe(2);
});

test('상세 404를 예시로 대체하지 않고 재시도 후 null을 구분한다', async ({ page }) => {
  await page.route('**/api/v1/inquiries?**', (route) =>
    route.fulfill({ json: success(listPage([item])) }),
  );
  let attempts = 0;
  await page.route(`**/api/v1/inquiries/${item.feedback_id}`, (route) =>
    ++attempts === 1
      ? route.fulfill({
          status: 404,
          json: { isSuccess: false, code: 'FEEDBACK_404_002', message: '문의를 찾을 수 없습니다.' },
        })
      : route.fulfill({
          json: success({
            ...detail,
            comment: null,
            status: 'CLOSED',
            scene: { ...item.scene, clip_title: null },
          }),
        }),
  );
  await page.goto('/search');
  await page.getByRole('button', { name: '문의 사항', exact: true }).click();
  await page.getByRole('button', { name: /^서버에 저장된 문의 서버 영상 제목/ }).click();
  const dialog = page.getByRole('dialog', { name: '문의 상세', exact: true });
  await expect(dialog.getByRole('alert')).toContainText('문의를 찾을 수 없습니다.');
  await dialog.getByRole('button', { name: '문의 상세 다시 시도' }).click();
  await expect(dialog.getByText('제목 없는 영상', { exact: true })).toBeVisible();
  await expect(dialog.getByText('추가 설명 없이 접수된 문의입니다.')).toBeVisible();
  await expect(dialog.getByText('처리 결과 기록 없음')).toBeVisible();
  await expect(dialog.getByText('처리 사유가 기록되지 않았습니다.')).toBeVisible();
});

test('검색 결과에서 접수한 문의는 재조회·새로고침 뒤에도 실제 기록으로 표시한다', async ({
  page,
}) => {
  let submitted = false;
  let listRequests = 0;
  await page.route('**/api/v1/inquiries?**', (route) => {
    listRequests++;
    return route.fulfill({ json: success(listPage(submitted ? [item] : [])) });
  });
  await page.route('**/api/v1/search/results/101/inquiries', (route) => {
    expect(route.request().method()).toBe('POST');
    expect(route.request().postDataJSON()).toEqual({ comment: item.comment });
    submitted = true;
    return route.fulfill({ json: success({ feedbackId: item.feedback_id, status: 'OPEN' }) });
  });
  await page.goto('/search/results?q=장면');
  const trigger = page.getByRole('button', { name: '문의 사항', exact: true });
  const panel = page.getByRole('complementary', { name: '문의 사항', exact: true });
  await trigger.click();
  await expect(panel.getByText('접수한 문의가 없습니다.')).toBeVisible();
  await panel.getByRole('button', { name: '정보 패널 닫기' }).click();
  await page.getByRole('button', { name: '1위 실제 응답 장면 Preview 열기' }).click();
  await page.getByRole('button', { name: '이상해요', exact: true }).click();
  await page.getByRole('textbox', { name: '설명 (선택)' }).fill(item.comment);
  await page.getByRole('button', { name: '문의 접수', exact: true }).click();
  await expect(
    page.getByText(`문의 #${item.feedback_id}의 접수가 확인되었습니다.`, { exact: false }),
  ).toBeVisible();
  await trigger.click();
  await expect(panel.getByText(item.comment, { exact: true })).toBeVisible();
  expect(listRequests).toBeGreaterThanOrEqual(2);
  await page.reload();
  await trigger.click();
  await expect(panel.getByText(item.comment, { exact: true })).toBeVisible();
});
