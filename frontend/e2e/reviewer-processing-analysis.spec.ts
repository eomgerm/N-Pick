import { expect, test, type Route } from '@playwright/test';
import path from 'node:path';

const CLIP_TITLE = '추석 귀성길, 서울역과 주요 고속도로 현장을 연결한 오늘의 교통 상황 종합 보도';

async function success(route: Route, data: unknown) {
  await route.fulfill({ json: { isSuccess: true, code: 'COMM_200', message: '성공', data } });
}

function clipDetail() {
  return {
    clip: {
      clip_id: '21',
      title: CLIP_TITLE,
      source_type: 'archive',
      search_available: true,
      registered_by: { login_id: 'arch04' },
      active_pipeline_run_id: '31',
      created_at: '2026-09-16T01:00:00Z',
      updated_at: '2026-09-16T02:00:00Z',
      latest_run: {
        pipeline_run_id: '32',
        processing_no: 2,
        status: 'succeeded',
        error_code: null,
        created_at: '2026-09-16T01:00:00Z',
        started_at: '2026-09-16T01:00:00Z',
        finished_at: '2026-09-16T02:00:00Z',
      },
      progress: {
        record_status: 'available',
        current_stage: null,
        total_steps: 10,
        succeeded_steps: 10,
        skipped_steps: 0,
        failed_steps: 0,
      },
    },
    default_transcript_source: 'provided',
    has_subtitle: true,
    has_script: false,
    processing_details: {
      pipeline_run_id: '32',
      record_status: 'available',
      failed_stages: [],
      missing_channels: [],
      retryable: null,
      transcript: null,
      stages: [
        {
          name: 'scene_detection',
          status: 'succeeded',
          attempts: 1,
          max_attempts: 2,
          automatic_retryable: false,
          started_at: '2026-09-16T01:00:00Z',
          finished_at: '2026-09-16T01:01:00Z',
          error_code: null,
          reason_code: null,
          failed_attempts: [],
        },
      ],
    },
  };
}

function analysisResult() {
  return {
    clip_id: '21',
    pipeline_run_id: '32',
    search_applied: false,
    summary: {
      total_scenes: 2,
      captioned_scenes: 1,
      transcript_scenes: 1,
      tagged_scenes: 1,
    },
    items: [
      {
        scene_id: '41',
        scene_index: 1,
        start_time_ms: 1_000,
        end_time_ms: 4_000,
        representative_frame_timestamp_ms: 1_600,
        caption:
          '서울역 앞 도로를 가득 채운 차량 사이로 취재 기자가 현재 교통 상황을 설명하고 있다.',
        shot_type: 'wide',
        transcript: { text: '현재 서울역 주변 교통 상황입니다.', source: 'provided' },
        tags: [
          {
            tag_id: '51',
            type: 'location',
            name: '서울역',
            match_value: '서울역',
            scope: 'scene',
            source: 'vlm',
            verification: 'unverified',
          },
          {
            tag_id: '52',
            type: 'event',
            name: '추석 귀성길',
            match_value: '추석 귀성길',
            scope: 'clip',
            source: 'reviewer_feedback',
            verification: 'reviewer_verified',
          },
        ],
        ocr_texts: ['서울역', '1번 출구'],
      },
      {
        scene_id: '42',
        scene_index: 2,
        start_time_ms: 4_000,
        end_time_ms: 7_000,
        representative_frame_timestamp_ms: null,
        caption: null,
        shot_type: 'unknown',
        transcript: null,
        tags: [],
        ocr_texts: [],
      },
    ],
    page: 0,
    size: 20,
    total_elements: 2,
    total_pages: 1,
    has_next: false,
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

for (const width of [1440, 390]) {
  test(`분석 결과 ${width}px에서 파이프라인 아래 장면 산출물을 확인하고 원본 영상으로 이동한다`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.route('**/api/v1/clips/21', (route) => success(route, clipDetail()));
    await page.route('**/api/v1/clips/21/runs/32/scenes?*', (route) =>
      success(route, analysisResult()),
    );
    await page.route('**/api/v1/scenes/41/thumbnail', (route) =>
      route.fulfill({
        contentType: 'image/png',
        path: path.join(process.cwd(), 'public/images/news-scenes-triptych.png'),
      }),
    );
    await page.goto('/review?view=processing&clip=21');

    await expect(
      page.getByRole('heading', {
        name: CLIP_TITLE,
        exact: true,
      }),
    ).toBeVisible();
    await expect(page.getByRole('tablist', { name: '영상 처리 파이프라인 10단계' })).toBeVisible();
    const result = page.getByRole('region', { name: '분석 결과', exact: true });
    await expect(result).toBeVisible();
    await expect(page.getByRole('region', { name: '대사 처리 기록', exact: true })).toHaveCount(0);
    await expect(result).toContainText('총 2개 장면');
    await expect(result).toContainText('캡션 1/2');
    await expect(result).toContainText('대사 1/2');
    await expect(result).toContainText('태그 1/2');
    await expect(result).toContainText(
      '서울역 앞 도로를 가득 채운 차량 사이로 취재 기자가 현재 교통 상황을 설명하고 있다.',
    );
    await expect(result).toContainText('현재 서울역 주변 교통 상황입니다.');
    await expect(result).toContainText('제공 자막');
    await expect(result).toContainText('서울역');
    await expect(result).toContainText('추석 귀성길');
    await expect(result).toContainText('1번 출구');
    await expect(result).toContainText('생성된 캡션이 없습니다.');
    await result.getByRole('heading', { name: '장면 01' }).scrollIntoViewIfNeeded();
    await expect(result.locator('[data-thumbnail-state="ready"]')).toHaveCount(1);
    expect(await result.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
      true,
    );
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: testInfo.outputPath(`processing-analysis-${width}.png`),
      fullPage: true,
    });

    const video = page.locator('video');
    const originalVideo = await video.elementHandle();
    await result.getByRole('button', { name: '장면 1 영상에서 보기' }).click();
    await expect
      .poll(() => video.evaluate((element) => element.currentTime))
      .toBeGreaterThanOrEqual(1);
    expect(await video.evaluate((element, original) => element === original, originalVideo)).toBe(
      true,
    );
  });
}
