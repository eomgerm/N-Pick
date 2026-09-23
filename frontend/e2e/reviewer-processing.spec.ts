import { expect, test, type Page, type Route } from '@playwright/test';

function clip(id = '21', status = 'failed') {
  return {
    clip_id: id,
    title: `서버 영상 ${id}`,
    source_type: 'archive',
    search_available: true,
    registered_by: { login_id: 'arch04' },
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
    const status = url.searchParams.get('status');
    const failed = Array.from({ length: count }, (_, index) => clip(String(index + 21)));
    const succeeded = [clip('99', 'succeeded')];
    const items =
      status === 'succeeded' ? succeeded : status === null ? [...failed, ...succeeded] : failed;
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
// 처리 중인 영상이 있어 5초마다 다시 읽는 목록. size 를 따르고, 첫 응답 뒤로는 늦게 답해 조회 중 상태를 붙잡는다.
async function pollingClipList(page: Page, count = 45) {
  const requests: URL[] = [];
  await page.route('**/api/v1/clips?*', async (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    if (requests.length > 1) await new Promise((resolve) => setTimeout(resolve, 1_500));
    const currentPage = Number(url.searchParams.get('page'));
    const size = Number(url.searchParams.get('size'));
    const items = Array.from({ length: count }, (_, index) => clip(String(index + 21), 'running'));
    await success(route, {
      items: items.slice(currentPage * size, (currentPage + 1) * size),
      page: currentPage,
      size,
      total_elements: items.length,
      total_pages: Math.ceil(items.length / size),
      has_next: (currentPage + 1) * size < items.length,
      run_counts: { queued: 0, running: count, failed: 0, succeeded: 0, no_run: 0 },
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
  await page.goto('/review?view=processing');
  const list = page.getByRole('region', { name: '영상 목록' });
  const chips = page.getByRole('group', { name: '처리 상태' });
  await expect(chips.getByRole('button')).toHaveText([
    '전체 12',
    '처리 중 0',
    '확인 필요 11',
    '처리 완료 1',
  ]);
  await expect(list.getByRole('progressbar')).toHaveCount(0);
  await expect(list).toContainText('일부 처리 기록만 확인됨');
  // 등록자는 로그인 ID 까지만 공개한다 (docs/contracts/web-api.md §6.5).
  await expect(list).toContainText('등록자 arch04');
  await page.screenshot({ path: testInfo.outputPath('processing.png'), fullPage: true });
  await page.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page).toHaveURL(/progressPage=2/);
  await page.getByRole('button', { name: '서버 영상 31 처리 상세', exact: true }).click();
  await expect(page.getByRole('heading', { name: '서버 영상 31', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '영상 처리 상세' })).toContainText('등록자 arch04');
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
  await chips.getByRole('button', { name: '처리 완료 1', exact: true }).click();
  await expect(page).toHaveURL(/clipStatus=done/);
  // 한 페이지뿐이어도 페이지네이션은 사라지지 않는다. 문의 화면과 같은 규약.
  const pager = page.getByRole('navigation', { name: '영상 목록 페이지' });
  await expect(pager).toBeVisible();
  await expect(pager.getByRole('button', { name: '1페이지' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(pager.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
  await expect(pager.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
  await expect(page).not.toHaveURL(/progressPage=/);
  await expect(
    page.getByRole('button', { name: '서버 영상 99 처리 상세', exact: true }),
  ).toBeVisible();
  // 전체 칩은 status 를 아예 보내지 않고, 칩을 고르면 서버가 아는 값으로 펼쳐 보낸다.
  expect(
    requests.some((url) => !url.searchParams.has('status') && url.searchParams.get('page') === '1'),
  ).toBe(true);
  expect(requests.some((url) => url.searchParams.get('status') === 'succeeded')).toBe(true);
  expect(requests.every((url) => !url.searchParams.has('mine'))).toBe(true);
});

test('화살표 키는 칩 포커스만 옮기고 선택은 Enter 로만 바뀐다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await clipList(page);
  await page.goto('/review?view=processing');
  const chips = page.getByRole('group', { name: '처리 상태' });
  const all = chips.getByRole('button', { name: '전체 12', exact: true });
  const processing = chips.getByRole('button', { name: '처리 중 0', exact: true });
  const done = chips.getByRole('button', { name: '처리 완료 1', exact: true });

  await all.focus();
  await all.press('ArrowRight');
  await expect(processing).toBeFocused();
  await processing.press('End');
  await expect(done).toBeFocused();
  // 포커스만 옮겼으므로 선택과 URL 은 그대로다 — 뒤로가기가 칩 상태를 되짚지 않는다.
  await expect(all).toHaveAttribute('aria-pressed', 'true');
  await expect(done).toHaveAttribute('aria-pressed', 'false');
  await expect(page).not.toHaveURL(/clipStatus=/);

  await done.press('Enter');
  await expect(done).toHaveAttribute('aria-pressed', 'true');
  await expect(page).toHaveURL(/clipStatus=done/);
  await expect(
    page.getByRole('button', { name: '서버 영상 99 처리 상세', exact: true }),
  ).toBeVisible();
});

test('내 영상만 보기는 mine 을 싣고 끄면 파라미터를 지운다', async ({ page }) => {
  const requests = await clipList(page);
  await page.goto('/review?view=processing');
  const mine = page.getByRole('checkbox', { name: '내 영상만 보기' });
  // URL 이 상태의 정본이라 체크 표시는 라우팅 뒤에 따라온다. check() 의 즉시 검사와는 맞지 않는다.
  await mine.click();
  await expect(page).toHaveURL(/mine=true/);
  await expect(mine).toBeChecked();
  await expect(page.getByRole('region', { name: '영상 목록' })).toContainText('내가 등록한 영상');
  await expect
    .poll(() => requests.some((url) => url.searchParams.get('mine') === 'true'))
    .toBe(true);
  await mine.click();
  await expect(page).not.toHaveURL(/mine=/);
  await expect(mine).not.toBeChecked();
});

test('상세는 실행 중 polling하고 완료되면 멈추며 실제 미디어 URL을 사용한다', async ({ page }) => {
  let reads = 0;
  let completeRead: (() => void) | undefined;
  const responseReady = new Promise<void>((resolve) => {
    completeRead = resolve;
  });
  await page.clock.install();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.route('**/api/v1/clips/21', async (route) => {
    reads++;
    if (reads === 2) await responseReady;
    return success(route, detail('21', reads === 1 ? 'running' : 'succeeded'));
  });
  await page.goto('/review?view=processing&clip=21');
  const region = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  await expect(region).toContainText('진행 중');
  const refreshStatus = region.getByRole('group', { name: '영상 상태 갱신 안내' });
  await expect(refreshStatus).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  const checkedAt = await refreshStatus.locator('time').getAttribute('datetime');
  const video = page.locator('video');
  await expect
    .poll(() => video.evaluate((element) => (element as HTMLVideoElement).duration))
    .toBeGreaterThan(0);
  const originalVideo = await video.elementHandle();
  await video.evaluate((element) => {
    (element as HTMLVideoElement).currentTime = 2;
  });
  const transcriptToggle = page
    .getByRole('region', { name: '대사 처리 기록', exact: true })
    .locator('summary');
  await transcriptToggle.focus();
  await page.clock.fastForward(5_100);
  await expect(refreshStatus).toContainText('처리 상태 확인 중…');
  expect(
    await refreshStatus
      .locator('svg')
      .evaluate((element) => getComputedStyle(element).animationName),
  ).toBe('none');
  completeRead!();
  await expect(region).toContainText('처리 완료');
  await expect(refreshStatus).toContainText('자동 확인이 종료되었습니다.');
  await expect(refreshStatus.locator('time')).not.toHaveAttribute('datetime', checkedAt!);
  await expect(transcriptToggle).toBeFocused();
  expect(await video.evaluate((element, original) => element === original, originalVideo)).toBe(
    true,
  );
  expect(
    await video.evaluate((element) => (element as HTMLVideoElement).currentTime),
  ).toBeGreaterThanOrEqual(2);
  await expect(region.getByRole('status').locator('time')).toHaveCount(0);
  const settledReads = reads;
  await page.clock.fastForward(15_000);
  expect(reads).toBe(settledReads);
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
  await page.goto('/review?view=processing');
  await expect(page.getByRole('main').getByRole('alert')).toContainText('영상 조회 실패');
  await expect(page.getByRole('main').getByRole('alert')).not.toContainText('CLIP_QUERY_503');
  // 오류·재시도 UI 는 사라진 요약 카드에서 패널 안으로 옮겼다. 칩 건수는 아직 모른다.
  await expect(page.getByRole('group', { name: '처리 상태' })).toContainText('전체 —');
  await expect(page.getByRole('region', { name: '영상 목록' })).toContainText(
    '최신 목록을 불러오지 못했습니다.',
  );
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
  await page.goto('/review?view=processing');
  const noRunList = page.getByRole('region', { name: '영상 목록' });
  await expect(noRunList).toContainText('처리 기록 없음');
  await expect(noRunList).toContainText('검색 미제공');
  // FRD F-02 어휘. archive 는 '자료 영상'이다.
  await expect(noRunList).toContainText('자료 영상');
  await expect(noRunList.getByRole('progressbar')).toHaveCount(0);
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
  await page.goto('/review?view=processing&clipStatus=done');
  await page.getByRole('link', { name: '영상 등록' }).click();
  await expect(page).toHaveURL(/view=upload/);
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  await page.locator('#registration-title').fill('등록 요청 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page).toHaveURL(/view=processing&clip=21/);
  // 등록 직후에는 칩 필터를 풀어야 방금 올린 영상(no_run)이 목록에 남는다.
  await expect(page).not.toHaveURL(/clipStatus=/);
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toContainText(
    '영상이 등록되었습니다.',
  );
  await expect(page.getByRole('status', { name: '영상 등록 결과' })).toContainText(
    'preview-fixture.mp4 · 처리 대기 상태',
  );
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
  const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  const noRunNotice = overview.getByText(
    '처리 기록을 확인하고 있습니다. 기록이 준비되면 자동으로 표시합니다.',
  );
  await expect(noRunNotice).toBeVisible();
  await expect(overview).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  for (const state of ['처리 대기', '진행 중', '처리 완료']) {
    await page.clock.fastForward(5_100);
    await expect(overview).toContainText(state);
    if (state === '처리 대기')
      await expect(overview).toContainText('등록된 영상의 분석 시작을 기다리고 있습니다.');
  }
  await expect(overview).toContainText('자동 확인이 종료되었습니다.');
  await expect(noRunNotice).toHaveCount(0);
  await page.getByRole('button', { name: '처리 현황으로', exact: true }).click();
  await expect(page).toHaveURL(/view=processing$/);
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

test('이미 등록된 영상은 그 사실을 알리고 내가 입력한 이름 대신 기존 등록 정보를 보여준다', async ({
  page,
}) => {
  await page.route('**/api/v1/clips', async (route) => {
    expect(route.request().method()).toBe('POST');
    await success(route, {
      clip_id: '21',
      pipeline_run_id: '32',
      status: 'queued',
      outcome: 'duplicate_other',
    });
  });
  await page.route('**/api/v1/clips/21', (route) => success(route, detail('21', 'succeeded')));
  await page.goto('/review?view=upload');
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  await page.locator('#registration-title').fill('내가 붙인 제목');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect(page).toHaveURL(/view=processing&clip=21/);
  const notice = page.getByRole('status', { name: '영상 등록 결과' });
  await expect(notice).toContainText('다른 사용자가 이미 등록한 영상입니다.');
  await expect(notice).toContainText('이번에 입력한 제목과 날짜는 저장되지 않았습니다.');
  // 등록 성공으로 읽히는 문구와 내 로컬 파일명이 남아 있으면 남의 영상을 내 것으로 오해한다.
  await expect(notice).not.toContainText('영상이 등록되었습니다.');
  await expect(notice).not.toContainText('preview-fixture.mp4');
  await expect(page.getByRole('heading', { name: '서버 영상 21', exact: true })).toBeVisible();
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
  await page.goto('/review?view=processing&clip=21');
  const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
  const refresh = overview.getByRole('button', { name: '상태 새로고침', exact: true });
  await expect(overview).toContainText('처리 기록 없음');
  await expect(overview).toContainText('처리 기록을 확인하고 있습니다.');
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(5_100);
  await expect.poll(() => reads).toBe(2);
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(60_000);
  await expect.poll(() => reads).toBe(3);
  await expect(refresh).toBeEnabled();
  await page.clock.fastForward(60_000);
  expect(reads).toBe(3);
  await expect(overview).toContainText('‘상태 새로고침’으로 다시 확인해 주세요.');
  await expect(overview).not.toContainText('처리 기록을 확인하고 있습니다.');
  await expect(overview).toContainText('자동 확인이 종료되었습니다.');
  hasRun = true;
  await refresh.click();
  await expect(overview).toContainText('진행 중');
  await expect(overview).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  await expect(overview.getByText(/아직 처리 기록이 없습니다/)).toHaveCount(0);
  expect(reads).toBe(4);
});

test('조회 오류에서 자동 확인과 성공 시각을 멈추고 수동 복구 뒤 다시 조회한다', async ({
  page,
}) => {
  let reads = 0;
  await page.clock.install();
  await page.route('**/api/v1/clips/21', async (route) => {
    reads++;
    if (reads === 2) {
      await route.fulfill({
        status: 503,
        json: { isSuccess: false, code: 'CLIP_QUERY_503', message: '처리 상태 조회 실패' },
      });
      return;
    }
    await success(route, detail('21', reads < 4 ? 'running' : 'succeeded'));
  });
  await page.goto('/review?view=processing&tab=uploads&clip=21');
  const status = page.getByRole('group', { name: '영상 상태 갱신 안내' });
  await expect(status).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  const checkedAt = await status.locator('time').getAttribute('datetime');
  await page.clock.fastForward(5_100);
  await expect(status).toContainText('조회 오류로 자동 확인을 중단했습니다.');
  await expect(status.locator('time')).toHaveAttribute('datetime', checkedAt!);
  await expect(page.getByRole('region', { name: '처리 상세 조회 오류' })).toContainText(
    '아래는 마지막으로 확인한 기록입니다.',
  );
  await page.clock.fastForward(15_000);
  expect(reads).toBe(2);
  await page.getByRole('button', { name: '처리 상세 다시 시도' }).click();
  await expect(status).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  await expect(status.locator('time')).not.toHaveAttribute('datetime', checkedAt!);
  await page.clock.fastForward(5_100);
  await expect(status).toContainText('자동 확인이 종료되었습니다.');
  expect(reads).toBe(4);
});

test('오프라인에서는 자동 확인 중으로 안내하지 않고 재연결하면 복구한다', async ({
  page,
  context,
}) => {
  await page.clock.install();
  await page.route('**/api/v1/clips/21', (route) => success(route, detail('21', 'running')));
  await page.goto('/review?view=processing&tab=uploads&clip=21');
  const status = page.getByRole('group', { name: '영상 상태 갱신 안내' });
  await expect(status).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  const checkedAt = await status.locator('time').getAttribute('datetime');
  await context.setOffline(true);
  await expect(status).toContainText('자동 확인이 일시 중지되었습니다.');
  await page.clock.fastForward(5_100);
  await expect(status).not.toContainText('처리 상태 확인 중');
  await expect(status.locator('time')).toHaveAttribute('datetime', checkedAt!);
  await context.setOffline(false);
  await expect(status).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
});

for (const isOffline of [false, true]) {
  test(`등록 1분 경계는 ${isOffline ? '오프라인 대기 중에도' : '다음 조회 전에'} 안내를 종료한다`, async ({
    page,
    context,
  }) => {
    const createdAt = '2026-09-17T01:00:00Z';
    await page.clock.install({
      time: new Date(Date.parse(createdAt) + (isOffline ? 54_000 : 59_000)),
    });
    await page.route('**/api/v1/clips/21', (route) =>
      success(route, {
        ...detail(),
        clip: { ...clip(), created_at: createdAt, latest_run: null, progress: null },
        processing_details: null,
      }),
    );
    await page.goto('/review?view=processing&tab=uploads&clip=21');
    const overview = page.getByRole('region', { name: '영상 처리 상세', exact: true });
    await expect(overview).toContainText('처리 기록을 확인하고 있습니다.');
    const checkedAt = await overview.locator('time').getAttribute('datetime');
    if (isOffline) {
      await context.setOffline(true);
      await page.clock.fastForward(5_100);
      await expect(overview).toContainText('자동 확인이 일시 중지되었습니다.');
    }
    await page.clock.fastForward(1_100);
    await expect(overview).toContainText('자동 확인이 종료되었습니다.');
    await expect(overview).not.toContainText('처리 기록을 확인하고 있습니다.');
    await expect(overview.getByRole('status')).toContainText('처리 기록 없음');
    await expect(overview.locator('time')).toHaveAttribute('datetime', checkedAt!);
  });
}

test('영상 목록 갱신 안내는 완료 필터 건수 변경 뒤 자동 확인을 종료한다', async ({ page }) => {
  let reads = 0;
  await page.clock.install();
  await page.route('**/api/v1/clips?*', (route) => {
    reads++;
    return success(route, {
      items: reads === 1 ? [clip('21', 'running')] : [],
      page: 0,
      size: 10,
      total_elements: reads === 1 ? 1 : 0,
      total_pages: reads === 1 ? 1 : 0,
      has_next: false,
      run_counts: {
        queued: 0,
        running: reads === 1 ? 1 : 0,
        failed: 0,
        succeeded: reads === 1 ? 0 : 1,
        no_run: 0,
      },
    });
  });
  await page.goto('/review?view=processing');
  const summary = page.getByRole('region', { name: '영상 목록', exact: true });
  await expect(summary).toContainText('5초마다 처리 상태를 자동으로 확인합니다.');
  await expect(summary.getByRole('group', { name: '영상 상태 갱신 안내' })).toHaveCount(1);
  await page.clock.fastForward(5_100);
  await expect(summary).toContainText('자동 확인이 종료되었습니다.');
  await expect(summary.getByRole('button', { name: '처리 완료 1', exact: true })).toBeVisible();
  await page.clock.fastForward(15_000);
  expect(reads).toBe(2);
});

test('음성 인식은 직접 상태와 동일 실행 단계를 사용하며 실패·생략·미확인·0건을 보존한다', async ({
  page,
}) => {
  const cases = [
    { direct: 'pending', stage: 'running', expected: '대기' },
    { direct: 'running', stage: 'succeeded', expected: '처리 중' },
    { direct: 'succeeded', stage: 'unknown', expected: '완료' },
    { direct: 'failed', stage: 'succeeded', expected: '실패' },
    { direct: 'skipped', stage: 'running', expected: '생략' },
    { direct: null, stage: 'pending', expected: '대기' },
    { direct: 'unknown', stage: 'running', expected: '처리 중' },
    { direct: null, stage: 'unknown', expected: '상태 정보 없음' },
  ];
  let current = cases[0];
  await page.route('**/api/v1/clips/21', (route) => {
    const response = detail('21', 'succeeded');
    return success(route, {
      ...response,
      processing_details: {
        ...response.processing_details,
        stages: [{ ...response.processing_details.stages[0], name: 'asr', status: current.stage }],
        transcript: {
          record_status: 'unavailable',
          asr_status: current.direct,
          asr_required: false,
          asr_segment_count: 0,
        },
      },
    });
  });
  await page.goto('/review?view=processing&tab=uploads&clip=21');
  const transcript = page.getByRole('region', { name: '대사 처리 기록', exact: true });
  await transcript.locator('summary').click();
  const asrStatus = transcript
    .locator('dl > div')
    .filter({ has: page.getByText('음성 인식 상태', { exact: true }) })
    .locator('dd');
  const count = transcript
    .locator('dl > div')
    .filter({ has: page.getByText('음성 인식 후보 구간', { exact: true }) })
    .locator('dd');
  for (const scenario of cases) {
    current = scenario;
    await page.getByRole('button', { name: '상태 새로고침', exact: true }).click();
    await expect(asrStatus).toHaveText(scenario.expected);
    await expect(transcript).toContainText('처리 기록 미확인');
    await expect(count).toHaveText('0개');
  }
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
    await page.goto('/review?view=processing&clip=21');
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

test('자동 재조회 중에도 번호 입력은 포커스를 잃지 않고 목록 표시 개수를 따른다', async ({
  page,
}) => {
  const requests = await pollingClipList(page);
  await page.goto('/review?view=processing&progressSize=20');
  const pager = page.getByRole('navigation', { name: '영상 목록 페이지' });
  const pageInput = pager.getByRole('textbox', { name: '이동할 페이지 번호' });
  await expect(pager.getByRole('button', { name: '3페이지' })).toBeVisible();
  expect(requests[0].searchParams.get('size')).toBe('20');
  await pageInput.fill('2');
  await expect.poll(() => requests.length, { timeout: 10_000 }).toBeGreaterThan(1);
  await expect(pageInput).toBeEnabled();
  await expect(pageInput).toBeFocused();
  await pageInput.press('Enter');
  await expect(page).toHaveURL(/progressSize=20&progressPage=2$/);
});

test('범위 밖 안내는 필터를 바꾸면 지워진다', async ({ page }) => {
  await pollingClipList(page);
  await page.goto('/review?view=processing');
  const pager = page.getByRole('navigation', { name: '영상 목록 페이지' });
  const pageInput = pager.getByRole('textbox', { name: '이동할 페이지 번호' });
  await pageInput.fill('9');
  await pageInput.press('Enter');
  await expect(pager.getByRole('alert')).toHaveText('1~5 사이의 페이지 번호를 입력해 주세요.');
  await page.getByRole('checkbox', { name: '내 영상만 보기' }).click();
  await expect(page).toHaveURL(/mine=true/);
  await expect(pager.getByRole('alert')).toHaveCount(0);
  await expect(pageInput).toHaveValue('');
  await expect(pageInput).not.toHaveAttribute('aria-invalid', 'true');
});

test('허용하지 않는 목록 표시 개수는 URL 에서 걷어 낸다', async ({ page }) => {
  await pollingClipList(page);
  await page.goto('/review?view=processing&progressSize=30');
  await expect(page).not.toHaveURL(/progressSize=/);
  await expect(page.getByRole('combobox', { name: '목록 표시 개수' })).toHaveValue('10');
});

test('다음 쪽을 읽는 동안에도 번호와 포커스를 유지하고 번호 이동 뒤에도 입력칸에 머문다', async ({
  page,
}) => {
  const requests = await pollingClipList(page);
  await page.goto('/review?view=processing');
  const pager = page.getByRole('navigation', { name: '영상 목록 페이지' });
  await pager.getByRole('button', { name: '2페이지' }).click();
  // 응답이 1.5초 늦게 온다. 그동안 총 쪽수를 잃으면 번호가 1 하나로 줄고 누른 버튼이 사라진다.
  await expect(pager.getByRole('button', { name: '5페이지' })).toBeVisible();
  await expect(pager.getByRole('button', { name: '2페이지' })).toBeFocused();
  // 앞 쪽 행을 보여 주는 동안은 새 쪽이 아님을 알린다.
  await expect(page.getByRole('region', { name: '영상 목록' }).getByRole('list')).toHaveAttribute(
    'aria-busy',
    'true',
  );
  await expect.poll(() => requests.length).toBeGreaterThan(1);
  const pageInput = pager.getByRole('textbox', { name: '이동할 페이지 번호' });
  await pageInput.fill('4');
  await pageInput.press('Enter');
  await expect(page).toHaveURL(/progressPage=4$/);
  await expect(pageInput).toBeFocused();
});

test('목록 표시 개수를 바꿔도 셀렉트에 포커스가 남는다', async ({ page }) => {
  await pollingClipList(page);
  await page.goto('/review?view=processing');
  const select = page.getByRole('combobox', { name: '목록 표시 개수' });
  // 제목이 마운트될 때 포커스를 가져가므로 목록이 뜬 뒤에 둔다.
  // selectOption 은 포커스를 옮기지 않는다. 키보드 사용자처럼 먼저 포커스를 둔다.
  await expect(page.getByRole('heading', { name: '영상 처리 현황' })).toBeFocused();
  await select.focus();
  await select.selectOption('20');
  await expect(page).toHaveURL(/progressSize=20/);
  await expect(page.getByRole('region', { name: '영상 목록' }).getByRole('listitem')).toHaveCount(
    20,
  );
  await expect(select).toBeFocused();
});

test('앞 쪽의 옛 총 쪽수로 새 쪽을 잘라 내지 않는다', async ({ page }) => {
  // 캐시를 비우려면 쓰지 않는 쿼리의 gcTime(5분)을 넘겨야 한다.
  await page.clock.install();
  let count = 45;
  await page.route('**/api/v1/clips?*', async (route) => {
    const url = new URL(route.request().url());
    const currentPage = Number(url.searchParams.get('page'));
    const items = Array.from({ length: count }, (_, index) => clip(String(index + 21)));
    await success(route, {
      items: items.slice(currentPage * 10, (currentPage + 1) * 10),
      page: currentPage,
      size: 10,
      total_elements: items.length,
      total_pages: Math.ceil(items.length / 10),
      has_next: (currentPage + 1) * 10 < items.length,
      run_counts: { queued: 0, running: 0, failed: count, succeeded: 0, no_run: 0 },
    });
  });
  await page.goto('/review?view=processing&progressPage=4');
  const pager = page.getByRole('navigation', { name: '영상 목록 페이지' });
  await expect(pager.getByRole('button', { name: '4페이지' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  // 1쪽을 읽는 사이 영상이 줄어 3쪽이 된다. 4쪽 캐시는 시간이 지나 사라진다.
  count = 25;
  await pager.getByRole('button', { name: '1페이지' }).click();
  await expect(pager.getByRole('button', { name: '3페이지' })).toBeVisible();
  await expect(pager.getByRole('button', { name: '4페이지' })).toHaveCount(0);
  await page.clock.fastForward('06:00');
  // 다시 늘어난 뒤 뒤로 가 4쪽을 연다. 4쪽 응답 전의 1쪽 총계(3쪽)로 잘라 내면 안 된다.
  count = 45;
  await page.goBack();
  await expect(pager.getByRole('button', { name: '4페이지' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page).toHaveURL(/progressPage=4$/);
});
