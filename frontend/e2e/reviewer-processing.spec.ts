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
  await expect(page.getByRole('region', { name: '최신 처리 단계' })).toContainText('처리 실패');
  await expect(page.getByRole('region', { name: '최신 처리 단계' })).not.toContainText(
    'WORKER_BUSY',
  );
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
  await expect(page.getByRole('main').getByRole('alert')).toContainText('영상 조회 실패');
  await expect(page.getByRole('main').getByRole('alert')).not.toContainText('CLIP_QUERY_503');
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
  await page.route('**/api/v1/clips/21', (route) =>
    success(route, {
      ...detail(),
      clip: {
        ...clip(),
        title: null,
        search_available: false,
        active_pipeline_run_id: null,
        latest_run: null,
        progress: null,
      },
      processing_details: null,
    }),
  );
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
  await page.getByRole('button', { name: '서버 영상 21 처리 상세', exact: true }).click();
  await expect(page.getByRole('heading', { name: '제목 없는 영상', exact: true })).toBeVisible();
  const pipeline = page.getByRole('tablist', { name: '영상 처리 파이프라인 10단계' });
  await expect(pipeline.getByRole('tab')).toHaveCount(10);
  await expect(pipeline.getByRole('tab', { name: /기록 없음$/ })).toHaveCount(10);
  await expect(page.getByRole('tabpanel')).toContainText('처리 상태를 확인할 수 없습니다.');
  await expect(page.getByRole('progressbar')).toHaveCount(0);
});

test('등록 성공 뒤 서버 ID로 처리 상세를 조회하고 새로고침해도 유지한다', async ({ page }) => {
  let registrations = 0;
  let reads = 0;
  const createdAt = '2026-09-17T01:00:00Z';
  await page.clock.install({ time: new Date(createdAt) });
  await page.route('**/api/v1/clips', async (route) => {
    expect(route.request().method()).toBe('POST');
    registrations++;
    await success(route, { clip_id: '21', pipeline_run_id: '32', status: 'queued' });
  });
  await page.route('**/api/v1/clips/21', async (route) => {
    reads++;
    const response = detail('21', reads === 2 ? 'queued' : reads === 3 ? 'running' : 'succeeded');
    await success(
      route,
      reads === 1
        ? {
            ...response,
            clip: {
              ...response.clip,
              created_at: createdAt,
              search_available: false,
              active_pipeline_run_id: null,
              latest_run: null,
              progress: null,
            },
            processing_details: null,
            default_transcript_source: 'none',
          }
        : response,
    );
  });
  await page.route('**/api/v1/clips?*', (route) =>
    success(route, {
      items: [clip('21', 'succeeded')],
      page: 0,
      size: 10,
      total_elements: 1,
      total_pages: 1,
      has_next: false,
      run_counts: { queued: 0, running: 0, failed: 0, succeeded: 1, no_run: 0 },
    }),
  );
  await page.goto('/review?view=upload');
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  await page.locator('#registration-title').fill('등록 요청 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page).toHaveURL(/view=processing&tab=uploads&clip=21/);
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toContainText(
    '영상이 등록되었습니다.',
  );
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toContainText(
    'preview-fixture.mp4 · 처리 대기 상태',
  );
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
  const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  const noRunNotice = overview.getByText(
    '아직 처리 기록이 없습니다. 잠시 후 ‘상태 새로고침’으로 다시 확인해 주세요.',
  );
  await expect(noRunNotice).toBeVisible();
  for (const state of ['처리 대기', '진행 중', '처리 완료']) {
    await page.clock.fastForward(5_100);
    await expect(overview).toContainText(state);
  }
  await expect(noRunNotice).toHaveCount(0);
  await page.getByRole('button', { name: '처리 현황으로', exact: true }).click();
  await expect(page).toHaveURL(/view=processing&tab=uploads$/);
  await expect(
    page.getByRole('button', { name: '서버 영상 21 처리 상세', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toHaveCount(0);
  await page.getByRole('button', { name: '서버 영상 21 처리 상세', exact: true }).click();
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: '영상 처리 상세', exact: true })).toContainText(
    '처리 완료',
  );
  const settledReads = reads;
  await page.clock.fastForward(15_000);
  expect(reads).toBe(settledReads);
  await page.reload();
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
  expect(registrations).toBe(1);
  expect(reads).toBeGreaterThanOrEqual(2);
});

test('자막과 대본의 선택·오류·삭제를 알리고 자막 드롭을 지원한다', async ({ page }) => {
  await page.goto('/review?view=upload');

  const emptyTransfer = await page.evaluateHandle(() => new DataTransfer());
  await page.locator('label[data-kind="subtitle"]').dispatchEvent('drop', {
    dataTransfer: emptyTransfer,
  });
  await expect(page.locator('#subtitle-error')).toHaveCount(0);

  await page.locator('#subtitle-file').setInputFiles({
    name: '잘못된-자막.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('자막이 아님'),
  });
  await expect(page.locator('#subtitle-error')).toHaveText(
    '자막 파일은 SRT, VTT 또는 승인된 JSON 형식으로 선택해 주세요.',
  );
  await expect(page.getByRole('status')).toHaveText(/자막 파일을 선택하지 못했습니다/);

  const subtitleTransfer = await page.evaluateHandle(() => {
    const transfer = new DataTransfer();
    transfer.items.add(new File(['1\n00:00:00,000 --> 00:00:01,000\n뉴스'], '뉴스.srt'));
    return transfer;
  });
  await page.locator('label[data-kind="subtitle"]').dispatchEvent('drop', {
    dataTransfer: subtitleTransfer,
  });
  const subtitleList = page.getByRole('list', { name: '선택한 자막 파일' });
  await expect(subtitleList).toContainText('선택됨');
  await expect(subtitleList).toContainText('뉴스.srt');
  await expect(page.getByRole('status')).toHaveText('자막 파일 뉴스.srt이 선택되었습니다.');

  await page.locator('#script-file').setInputFiles({
    name: '취재대본.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('취재 대본'),
  });
  await expect(page.getByRole('list', { name: '선택한 일반 대본 파일' })).toContainText(
    '취재대본.txt',
  );
  await expect(page.getByRole('status')).toHaveText(
    '일반 대본 파일 취재대본.txt이 선택되었습니다.',
  );

  await page.getByRole('button', { name: '자막 파일 삭제' }).click();
  await expect(subtitleList).toHaveCount(0);
  await expect(page.getByRole('status')).toHaveText('자막 파일 뉴스.srt이 삭제되었습니다.');
});

test('최근 등록의 기록 없음 재조회는 1분 뒤 멈추고 수동 조회로 복구한다', async ({ page }) => {
  let reads = 0;
  let hasRun = false;
  const createdAt = '2026-09-17T01:00:00Z';
  await page.clock.install({ time: new Date(createdAt) });
  await page.route('**/api/v1/clips/21', (route) => {
    reads++;
    return success(
      route,
      hasRun
        ? detail('21', 'running')
        : {
            ...detail(),
            clip: {
              ...clip(),
              created_at: createdAt,
              search_available: false,
              active_pipeline_run_id: null,
              latest_run: null,
              progress: null,
            },
            processing_details: null,
            default_transcript_source: 'none',
          },
    );
  });
  await page.goto('/review?view=processing&tab=uploads&clip=21');
  const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  const refresh = overview.getByRole('button', { name: '상태 새로고침', exact: true });
  await expect(overview).toContainText('처리 기록 없음');
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(5_100);
  await expect.poll(() => reads).toBe(2);
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(60_000);
  await expect.poll(() => reads).toBe(3);
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(60_000);
  expect(reads).toBe(3);
  await expect(overview).toContainText('잠시 후 ‘상태 새로고침’으로 다시 확인해 주세요.');
  hasRun = true;
  await refresh.click();
  await expect(overview).toContainText('진행 중');
  await expect(overview.getByText(/아직 처리 기록이 없습니다/)).toHaveCount(0);
  expect(reads).toBe(4);
});

for (const width of [1440, 390, 320]) {
  test(`처리 상세 ${width}px에서 10단계 파이프라인을 hover·키보드·클릭으로 조회한다`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    const response = detail();
    await page.route('**/api/v1/clips/21', (route) =>
      success(route, {
        ...response,
        clip: {
          ...response.clip,
          title: '추석 귀성길, 서울역과 고속도로 현장',
          progress: {
            record_status: 'available',
            current_stage: null,
            total_steps: 10,
            succeeded_steps: 1,
            skipped_steps: 1,
            failed_steps: 1,
          },
        },
        processing_details: {
          ...response.processing_details,
          stages: [
            ...[
              ['scene_detection', 'succeeded'],
              ['asr', 'skipped'],
            ].map(([name, status]) => ({
              ...response.processing_details.stages[0],
              name,
              status,
              attempts: 1,
              started_at: '2026-09-16T01:00:00Z',
              finished_at: '2026-09-16T01:02:00Z',
              error_code: null,
              failed_attempts: [],
            })),
            ...response.processing_details.stages,
            ...[
              ['vlm_metadata', 'pending'],
              ['indexing', 'unknown'],
            ].map(([name, status]) => ({
              ...response.processing_details.stages[0],
              name,
              status,
              attempts: null,
              error_code: null,
              failed_attempts: [],
            })),
          ],
        },
      }),
    );
    await page.goto('/review?view=processing&tab=uploads&clip=21');
    const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
    const stages = page.getByRole('region', { name: '최신 처리 단계', exact: true });
    const header = page.getByRole('banner');
    await expect(header.getByText('영상 등록 처리 상세', { exact: true })).toBeVisible();
    await expect(overview.getByText('영상 등록 처리 상세', { exact: true })).toHaveCount(0);
    await expect(overview.getByRole('status')).toContainText('확인 필요');
    await expect(overview.getByRole('status')).toContainText('영상 처리를 완료하지 못했습니다.');
    await expect(overview).not.toContainText('STAGE_TIMEOUT');
    await expect(overview.getByText('검색 가능', { exact: true })).toBeVisible();
    await expect(stages.getByText(/처리 실패/)).toBeVisible();
    await expect(stages).not.toContainText('WORKER_BUSY');
    const stageBox = await stages.boundingBox();
    const media = page.getByRole('region', { name: '원본 영상', exact: true });
    if (width > 760) {
      const overviewBox = await overview.boundingBox();
      const mediaBox = await media.boundingBox();
      expect(mediaBox!.x).toBeGreaterThan(overviewBox!.x + overviewBox!.width);
      expect(Math.abs(mediaBox!.y - overviewBox!.y)).toBeLessThan(2);
      expect(stageBox!.y).toBeGreaterThan(overviewBox!.y + overviewBox!.height);
      const headerTitle = await header
        .getByText('영상 등록 처리 상세', { exact: true })
        .boundingBox();
      const back = await header
        .getByRole('button', { name: '처리 현황으로', exact: true })
        .boundingBox();
      expect(back!.x).toBeGreaterThan(headerTitle!.x + headerTitle!.width);
      expect(Math.abs(back!.x + back!.width - mediaBox!.x - mediaBox!.width)).toBeLessThan(2);
    }
    const tabs = stages.getByRole('tab');
    await expect(tabs).toHaveCount(10);
    await expect(tabs).toHaveText([
      '장면 나누기성공',
      '02대표 화면 추출기록 없음',
      '03영상 속 글자 읽기실패',
      '04대사 출처 선택기록 없음',
      '05음성 인식생략',
      '06장면별 대사 연결기록 없음',
      '07영상 설명 생성대기',
      '08개체 추출기록 없음',
      '09검색 임베딩 생성기록 없음',
      '10검색 반영미확인',
    ]);
    const rows = await tabs.evaluateAll((elements) =>
      elements.map((element) => element.getBoundingClientRect().y),
    );
    expect(new Set(rows).size).toBe(1);
    await expect
      .poll(() =>
        page.locator('video').evaluate((element) => (element as HTMLVideoElement).readyState),
      )
      .toBeGreaterThanOrEqual(2);
    await page.screenshot({
      path: testInfo.outputPath(`processing-detail-${width}.png`),
      fullPage: true,
    });
    await page.screenshot({ path: testInfo.outputPath(`processing-viewport-${width}.png`) });
    const firstStage = tabs.first();
    const record = stages.getByRole('tabpanel');
    await firstStage.hover();
    await expect(firstStage).toHaveAttribute('aria-selected', 'true');
    await expect(record.getByRole('heading', { name: '장면 나누기', exact: true })).toBeVisible();
    await expect(record).not.toContainText('WORKER_BUSY');
    await tabs.nth(2).hover();
    await expect(record).toContainText('처리 실패');
    await expect(record).not.toContainText('WORKER_BUSY');
    await firstStage.focus();
    await firstStage.press('ArrowRight');
    await expect(tabs.nth(1)).toBeFocused();
    await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true');
    await expect(record).toContainText('저장된 단계 기록이 없습니다.');
    await tabs.nth(1).press('End');
    await expect(tabs.last()).toBeFocused();
    await expect(record.getByRole('heading', { name: '검색 반영', exact: true })).toBeVisible();
    await tabs.nth(4).click();
    await expect(tabs.nth(4)).toHaveAttribute('aria-selected', 'true');
    await expect(record).toContainText('생략');
    const transcript = page.getByRole('region', { name: '대사 처리 기록', exact: true });
    await transcript.locator('summary').focus();
    await transcript.locator('summary').press('Enter');
    await expect(transcript.getByText('저장된 대사 선택 기록이 없습니다.')).toBeVisible();
    await transcript.locator('summary').press('Enter');
    for (const region of [overview, stages, media, transcript]) {
      expect(await region.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
        true,
      );
    }
  });
}
