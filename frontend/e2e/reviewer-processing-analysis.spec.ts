import { expect, test, type Route } from '@playwright/test';
import path from 'node:path';

const CLIP_TITLE = '추석 귀성길, 서울역과 주요 고속도로 현장을 연결한 오늘의 교통 상황 종합 보도';
const PIPELINE_STAGES = [
  'scene_detection',
  'frame_extraction',
  'ocr',
  'transcript_selection',
  'asr',
  'scene_transcript_mapping',
  'vlm_metadata',
  'entity_extraction',
  'text_embedding',
  'indexing',
] as const;
const STAGE_RESULT_TITLES = [
  '나눈 장면',
  '추출한 대표 화면',
  '읽어낸 화면 글자',
  '선택한 대사 출처',
  '음성 인식 결과',
  '연결한 장면 대사',
  '생성한 영상 설명',
  '추출한 검색 태그',
  '검색 표현 생성 결과',
  '검색 반영 결과',
] as const;

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
      stages: PIPELINE_STAGES.map((name, index) => {
        const minute = String(index).padStart(2, '0');
        const nextMinute = String(index + 1).padStart(2, '0');
        return {
          name,
          status: 'succeeded',
          attempts: 1,
          max_attempts: 2,
          automatic_retryable: false,
          started_at: `2026-09-16T01:${minute}:00Z`,
          finished_at: `2026-09-16T01:${nextMinute}:00Z`,
          error_code: null,
          reason_code: null,
          failed_attempts: [],
        };
      }),
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
    const stageResult = page.getByRole('region', { name: '단계별 처리 결과', exact: true });
    const finalResult = page.getByRole('region', { name: '최종 분석 결과', exact: true });
    await expect(stageResult).toBeVisible();
    await expect(finalResult).toBeVisible();
    await expect(page.getByRole('region', { name: '대사 처리 기록', exact: true })).toHaveCount(0);
    await expect(stageResult.getByRole('article')).toHaveCount(1);
    await expect(stageResult.getByRole('heading', { name: '나눈 장면' })).toBeVisible();
    await expect(finalResult.getByRole('heading', { name: '최종 분석 결과' })).toBeVisible();
    await expect(finalResult.getByRole('article')).toHaveCount(1);
    await expect(finalResult).toContainText('장면 1 / 2');
    await expect(finalResult).toContainText(
      '서울역 앞 도로를 가득 채운 차량 사이로 취재 기자가 현재 교통 상황을 설명하고 있다.',
    );
    await expect(finalResult).toContainText('현재 서울역 주변 교통 상황입니다.');
    await expect(finalResult).toContainText('서울역');
    await expect(finalResult).toContainText('1번 출구');
    await expect(finalResult).not.toContainText('생성된 영상 설명이 없습니다.');
    await finalResult.scrollIntoViewIfNeeded();
    await expect(finalResult.locator('[data-thumbnail-state="ready"]')).toHaveCount(1);
    expect(
      await finalResult.evaluate((element) => element.scrollWidth <= element.clientWidth),
    ).toBe(true);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: testInfo.outputPath(`processing-analysis-${width}.png`),
      fullPage: true,
    });
    const stages = page.getByRole('region', { name: '최신 처리 단계', exact: true });
    const tabs = stages.getByRole('tablist').getByRole('tab');
    for (let index = 0; index < PIPELINE_STAGES.length; index++) {
      await tabs.nth(index).click();
      await expect(tabs.nth(index)).toHaveAttribute('aria-selected', 'true');
      await expect(
        stageResult.getByRole('heading', { name: STAGE_RESULT_TITLES[index] }),
      ).toBeVisible();
      await expect(stageResult.getByRole('article')).toHaveCount(1);
      if (width === 1440) {
        await stageResult.screenshot({
          path: testInfo.outputPath(
            `processing-result-stage-${String(index + 1).padStart(2, '0')}.png`,
          ),
        });
      }
    }

    await expect(finalResult.getByRole('button', { name: '이전 장면' })).toBeDisabled();
    await finalResult.getByRole('button', { name: '다음 장면' }).click();
    await expect(finalResult).toContainText('장면 2 / 2');
    await expect(finalResult.getByRole('article')).toHaveCount(1);
    await expect(finalResult).not.toContainText('현재 서울역 주변 교통 상황입니다.');
    await finalResult.getByRole('button', { name: '이전 장면' }).click();
    await expect(finalResult).toContainText('장면 1 / 2');

    await tabs.nth(1).click();
    await expect(stageResult.locator('[data-thumbnail-state="ready"]')).toHaveCount(1);

    const video = page.locator('video');
    const originalVideo = await video.elementHandle();
    await finalResult.getByRole('button', { name: '장면 1 영상에서 보기' }).click();
    await expect
      .poll(() => video.evaluate((element) => element.currentTime))
      .toBeGreaterThanOrEqual(1);
    expect(await video.evaluate((element, original) => element === original, originalVideo)).toBe(
      true,
    );
  });
}
