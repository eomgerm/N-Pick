import { expect, test, type Page, type Route } from '@playwright/test';

function clip(id = '21', status = 'failed') {
  return {
    clip_id: id,
    title: `서버 영상 ${id}`,
    source_type: 'archive',
    search_available: true,
    active_pipeline_run_id: '31',
    created_at: '2026-09-16T01:00:00Z',
    updated_at: '2026-09-16T02:00:00Z',
    latest_run: {
      pipeline_run_id: '32',
      processing_no: 2,
      status,
      error_code: status === 'failed' ? 'STAGE_TIMEOUT' : null,
      created_at: '2026-09-16T01:00:00Z',
      started_at: '2026-09-16T01:00:00Z',
      finished_at: status === 'running' ? null : '2026-09-16T02:00:00Z',
    },
    progress: {
      record_status: 'partial',
      current_stage: null,
      total_steps: null,
      succeeded_steps: null,
      skipped_steps: null,
      failed_steps: null,
    },
  };
}
function detail(id = '21', status = 'failed') {
  return {
    clip: clip(id, status),
    default_transcript_source: 'provided',
    has_subtitle: true,
    has_script: false,
    processing_details: {
      pipeline_run_id: '32',
      record_status: 'partial',
      failed_stages: ['ocr'],
      missing_channels: null,
      retryable: null,
      transcript: null,
      stages: [
        {
          name: 'ocr',
          status: 'failed',
          attempts: 2,
          max_attempts: 2,
          automatic_retryable: false,
          started_at: null,
          finished_at: null,
          error_code: 'STAGE_TIMEOUT',
          reason_code: null,
          failed_attempts: [{ attempt: 1, error_code: 'WORKER_BUSY', finished_at: null }],
        },
      ],
    },
  };
}
async function success(route: Route, data: unknown) {
  await route.fulfill({ json: { isSuccess: true, code: 'COMM_200', message: '성공', data } });
}
async function clipList(page: Page, count = 11) {
  const requests: URL[] = [];
  await page.route('**/api/v1/clips?*', async (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    const currentPage = Number(url.searchParams.get('page'));
    const completed = url.searchParams.get('status') === 'succeeded';
    const items = completed
      ? [clip('99', 'succeeded')]
      : Array.from({ length: count }, (_, index) => clip(String(index + 21)));
    await success(route, {
      items: items.slice(currentPage * 10, (currentPage + 1) * 10),
      page: currentPage,
      size: 10,
      total_elements: items.length,
      total_pages: Math.ceil(items.length / 10),
      has_next: (currentPage + 1) * 10 < items.length,
      run_counts: { queued: 0, running: 0, failed: count, succeeded: 1, no_run: 0 },
    });
  });
  return requests;
}
test.beforeEach(async ({ page }) => {
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'test-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/review/inquiries?*', (route) =>
    success(route, {
      items: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
      statusCounts: { open: 8, reviewing: 0, closed: 12 },
    }),
  );
});

test('서버 전체 집계·필터·페이지를 사용하고 상세에서 목록 위치를 유지한다', async ({
  page,
}, testInfo) => {
  const requests = await clipList(page);
  await page.route('**/api/v1/clips/31', (route) => success(route, detail('31')));
  await page.goto('/review?view=processing&tab=uploads');
  await expect(page.getByRole('region', { name: '영상 등록 요약' })).toContainText('전체 12개');
  await expect(page.getByRole('region', { name: '문의 처리 요약' })).toContainText('전체 20개');
  await expect(page.getByRole('tabpanel').getByRole('progressbar')).toHaveCount(0);
  await expect(page.getByRole('tabpanel')).toContainText('일부 처리 기록만 확인됨');
  await page.screenshot({ path: testInfo.outputPath('processing.png'), fullPage: true });
  await page.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page).toHaveURL(/progressPage=2/);
  await page.getByRole('button', { name: '서버 영상 31 처리 상세', exact: true }).click();
  await expect(page.getByRole('heading', { name: '서버 영상 31', exact: true })).toBeVisible();
  await expect(
    page.getByText('이전 처리 결과로 검색을 제공하고 있습니다.', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('region', { name: '최신 처리 단계' })).toContainText('WORKER_BUSY');
  await expect(page.getByRole('button', { name: /다시 처리|재처리 요청/ })).toHaveCount(0);
  await page.getByRole('button', { name: '처리 현황으로', exact: true }).click();
  await expect(page).toHaveURL(/progressPage=2$/);
  await expect(
    page.getByRole('button', { name: '서버 영상 31 처리 상세', exact: true }),
  ).toBeVisible();
  await page.getByRole('tab', { name: '등록 완료 1', exact: true }).click();
  await expect(page).not.toHaveURL(/progressPage=/);
  await expect(
    page.getByRole('button', { name: '서버 영상 99 처리 상세', exact: true }),
  ).toBeVisible();
  expect(
    requests.some(
      (url) =>
        url.searchParams.get('status') === 'queued,running,failed,no_run' &&
        url.searchParams.get('page') === '1',
    ),
  ).toBe(true);
  expect(requests.some((url) => url.searchParams.get('status') === 'succeeded')).toBe(true);
});

test('키보드로 처리 탭을 전환해도 전환 후 포커스를 유지한다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await clipList(page);
  await page.goto('/review?view=processing');
  const inquiries = page.getByRole('tab', { name: '문의 처리 중 0', exact: true });
  const uploads = page.getByRole('tab', { name: '영상 등록 중 11', exact: true });
  const completed = page.getByRole('tab', { name: '등록 완료 1', exact: true });
  await inquiries.focus();
  await inquiries.press('ArrowRight');
  await expect(uploads).toHaveAttribute('aria-selected', 'true');
  await expect(uploads).toBeEnabled();
  await expect(uploads).toBeFocused();
  await uploads.press('End');
  await expect(completed).toHaveAttribute('aria-selected', 'true');
  await expect(completed).toBeEnabled();
  await expect(completed).toBeFocused();
  await expect(
    page.getByRole('button', { name: '서버 영상 99 처리 상세', exact: true }),
  ).toBeVisible();
});

test('상세는 실행 중 polling하고 완료되면 멈추며 실제 미디어 URL을 사용한다', async ({ page }) => {
  let reads = 0;
  await page.clock.install();
  await page.route('**/api/v1/clips/21', (route) => {
    reads++;
    return success(route, detail('21', reads === 1 ? 'running' : 'succeeded'));
  });
  await page.goto('/review?view=processing&tab=uploads&clip=21');
  const region = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  await expect(region).toContainText('진행 중');
  await page.clock.fastForward(5_100);
  await expect(region).toContainText('처리 완료');
  const settledReads = reads;
  await page.clock.fastForward(15_000);
  expect(reads).toBe(settledReads);
  const video = page.locator('video');
  await expect(video).toHaveAttribute('src', 'http://127.0.0.1:18116/api/v1/media/21');
  await expect(video).toHaveAttribute('crossorigin', 'use-credentials');
  await expect
    .poll(() => video.evaluate((element) => (element as HTMLVideoElement).duration))
    .toBeGreaterThan(0);
});

test('영상 API 오류를 데모로 대체하지 않고 문의 요약과 재조회는 계속 동작한다', async ({
  page,
}) => {
  await clipList(page, 1);
  await page.route(
    '**/api/v1/clips?*',
    async (route) => {
      await route.fulfill({
        status: 503,
        json: { isSuccess: false, code: 'CLIP_QUERY_503', message: '영상 조회 실패' },
      });
    },
    { times: 1 },
  );
  await page.goto('/review?view=processing&tab=uploads');
  await expect(page.getByRole('main').getByRole('alert')).toContainText('CLIP_QUERY_503');
  await expect(page.getByRole('region', { name: '문의 처리 요약' })).toContainText('전체 20개');
  await expect(page.getByRole('region', { name: '영상 등록 요약' })).toContainText('전체 —개');
  await expect(page.getByRole('tabpanel')).toContainText('최신 목록을 불러오지 못했습니다.');
  await page.getByRole('button', { name: '영상 현황 다시 시도' }).click();
  await expect(
    page.getByRole('button', { name: '서버 영상 21 처리 상세', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('main').getByRole('alert')).toHaveCount(0);
});

test('기록 없는 영상은 대기 또는 0단계 완료로 만들지 않는다', async ({ page }) => {
  await page.route('**/api/v1/clips?*', (route) =>
    success(route, {
      items: [
        {
          ...clip(),
          search_available: false,
          active_pipeline_run_id: null,
          latest_run: null,
          progress: null,
        },
      ],
      page: 0,
      size: 10,
      total_elements: 1,
      total_pages: 1,
      has_next: false,
      run_counts: { queued: 0, running: 0, failed: 0, succeeded: 0, no_run: 1 },
    }),
  );
  await page.goto('/review?view=processing&tab=uploads');
  await expect(page.getByRole('tabpanel')).toContainText('처리 기록 없음');
  await expect(page.getByRole('tabpanel')).toContainText('검색 미제공');
  await expect(page.getByRole('tabpanel').getByRole('progressbar')).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});

test('등록 성공 뒤 서버 ID로 처리 상세를 조회하고 새로고침해도 유지한다', async ({ page }) => {
  let registrations = 0;
  let reads = 0;
  await page.route('**/api/v1/clips', async (route) => {
    expect(route.request().method()).toBe('POST');
    registrations++;
    await success(route, { clip_id: '21', pipeline_run_id: '32', status: 'queued' });
  });
  await page.route('**/api/v1/clips/21', async (route) => {
    reads++;
    await success(route, detail('21', 'succeeded'));
  });
  await page.goto('/review?view=upload');
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  await page.locator('#registration-title').fill('등록 요청 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page).toHaveURL(/view=processing&tab=uploads&clip=21/);
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
  expect(registrations).toBe(1);
  expect(reads).toBeGreaterThanOrEqual(2);
});
