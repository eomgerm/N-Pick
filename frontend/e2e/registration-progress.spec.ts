import { expect, test, type Route } from '@playwright/test';

import { openRegistrationWithVideo } from './registration-helpers';

// 영상 등록 대기 화면과 처리 상세 10단계 진행 요약 (S15P21A501-325).

async function success(route: Route, data: unknown) {
  await route.fulfill({ json: { isSuccess: true, code: 'COMM_200', message: '성공', data } });
}

function stage(name: string, status: string) {
  return {
    name,
    status,
    attempts: status === 'pending' ? 0 : 1,
    max_attempts: 1,
    automatic_retryable: false,
    started_at: null,
    finished_at: null,
    error_code: null,
    reason_code: null,
    failed_attempts: [],
  };
}

function detail(runStatus: string, stages: ReturnType<typeof stage>[]) {
  return {
    clip: {
      clip_id: '21',
      title: '진행 표시 영상',
      source_type: 'broadcast',
      search_available: false,
      registered_by: { login_id: 'arch04' },
      active_pipeline_run_id: null,
      created_at: '2026-09-29T01:00:00Z',
      updated_at: '2026-09-29T01:00:00Z',
      latest_run: {
        pipeline_run_id: '32',
        processing_no: 1,
        status: runStatus,
        error_code: null,
        created_at: '2026-09-29T01:00:00Z',
        started_at: '2026-09-29T01:00:00Z',
        finished_at: null,
      },
      progress: {
        record_status: 'available',
        current_stage: null,
        total_steps: 10,
        succeeded_steps: stages.filter((item) => item.status === 'succeeded').length,
        skipped_steps: 0,
        failed_steps: 0,
      },
    },
    default_transcript_source: 'provided',
    has_subtitle: false,
    has_script: false,
    processing_details: {
      pipeline_run_id: '32',
      record_status: 'available',
      failed_stages: [],
      missing_channels: null,
      retryable: null,
      transcript: null,
      stages,
    },
  };
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
      statusCounts: { open: 0, reviewing: 0, closed: 0 },
    }),
  );
});

test('등록 요청 중에는 폼 위에 대기 화면을 띄우고 전송이 끝나면 서버 확인 단계로 넘긴다', async ({
  page,
}) => {
  // POST /clips 는 가로채지 않는다 — global-setup mock 이 본문을 다 받고 2.5초 뒤 답한다.
  await page.route('**/api/v1/clips/21', (route) =>
    success(route, detail('queued', [stage('scene_detection', 'pending')])),
  );

  await openRegistrationWithVideo(page);
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();

  const panel = page.getByRole('dialog', {
    name: /영상을 보내고 있어요|서버가 영상을 확인하고 있어요/,
  });
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('preview-fixture.mp4');
  // 작은 파일은 곧바로 다 보내지므로 서버 응답을 기다리는 확인 단계에 머문다.
  await expect(panel.getByRole('heading', { name: '서버가 영상을 확인하고 있어요' })).toBeVisible();
  await expect(panel.getByRole('listitem').filter({ hasText: '서버 확인' })).toHaveAttribute(
    'aria-current',
    'step',
  );
  await expect(panel.getByRole('progressbar', { name: '서버 확인 진행 중' })).toBeVisible();

  await expect(page).toHaveURL(/view=processing&clip=21/);
  await expect(panel).toHaveCount(0);
});

test('등록이 실패하면 대기 화면을 닫고 입력을 유지한 폼으로 돌아간다', async ({ page }) => {
  await page.route('**/api/v1/clips', (route) =>
    route.fulfill({
      status: 503,
      json: { isSuccess: false, code: 'COMM_503', message: '잠시 후 다시 시도해 주세요.' },
    }),
  );
  await openRegistrationWithVideo(page);
  await page.locator('#registration-title').fill('유지할 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();

  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('#registration-title')).toHaveValue('유지할 제목');
  await expect(page.locator('#video-selection')).toContainText('preview-fixture.mp4');
});

test('처리 상세는 완료 단계 수와 지금 진행 중인 단계를 막대와 문구로 보여 준다', async ({
  page,
}) => {
  await page.route('**/api/v1/clips/21', (route) =>
    success(
      route,
      detail('running', [
        stage('scene_detection', 'succeeded'),
        stage('frame_extraction', 'succeeded'),
        stage('ocr', 'running'),
        stage('transcript_selection', 'pending'),
      ]),
    ),
  );
  await page.route('**/api/v1/clips/21/runs/32/scenes?*', (route) =>
    success(route, {
      items: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
      has_next: false,
    }),
  );
  await page.goto('/review?view=processing&clip=21');

  const bar = page.getByRole('progressbar', { name: '영상 처리 진행률' });
  await expect(bar).toHaveAttribute('aria-valuenow', '2');
  await expect(bar).toHaveAttribute('aria-valuemax', '10');
  await expect(page.getByText('2/10 완료 · 지금 영상 속 글자 읽기 진행 중')).toBeVisible();
  await expect(page.getByRole('tab', { name: /3단계 영상 속 글자 읽기 · 진행 중/ })).toBeVisible();
});

test('작업자가 아직 가져가지 않은 처리는 대기 중이라고 알린다', async ({ page }) => {
  await page.route('**/api/v1/clips/21', (route) =>
    success(route, detail('queued', [stage('scene_detection', 'pending')])),
  );
  await page.route('**/api/v1/clips/21/runs/32/scenes?*', (route) =>
    success(route, {
      items: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
      has_next: false,
    }),
  );
  await page.goto('/review?view=processing&clip=21');

  await expect(page.getByText('0/10 완료 · 처리 서버가 작업을 가져가길 기다리는 중')).toBeVisible();
});
