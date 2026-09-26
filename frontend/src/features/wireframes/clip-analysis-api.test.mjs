import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice(2)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { getClipAnalysisScenes, parseClipAnalysisScenes } = await import('./clip-analysis-api.ts');

const response = {
  clip_id: '9223372036854775807',
  pipeline_run_id: '32',
  search_applied: false,
  summary: {
    total_scenes: 2,
    captioned_scenes: 1,
    transcript_scenes: 1,
    tagged_scenes: 1,
    embedded_scenes: 1,
  },
  items: [
    {
      scene_id: '41',
      scene_index: 1,
      start_time_ms: 0,
      end_time_ms: 4200,
      representative_frame_timestamp_ms: 1600,
      caption: '서울역 앞 도로에 차량이 이동한다.',
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
      ],
      ocr_texts: ['서울역', '1번 출구'],
      embedding_ready: true,
    },
    {
      scene_id: '42',
      scene_index: 2,
      start_time_ms: 4200,
      end_time_ms: 9000,
      representative_frame_timestamp_ms: null,
      caption: null,
      shot_type: 'unknown',
      transcript: null,
      tags: [],
      ocr_texts: [],
      embedding_ready: false,
    },
  ],
  page: 0,
  size: 20,
  total_elements: 2,
  total_pages: 1,
  has_next: false,
};

test('bigint ID와 부분 분석 결과를 손실 없이 읽는다', () => {
  const parsed = parseClipAnalysisScenes(response);
  assert.equal(parsed.clip_id, '9223372036854775807');
  assert.equal(parsed.items[0].representative_frame_timestamp_ms, 1600);
  assert.equal(parsed.items[0].transcript.source, 'provided');
  assert.equal(parsed.items[0].tags[0].verification, 'unverified');
  assert.equal(parsed.items[0].embedding_ready, true);
  assert.equal(parsed.items[1].caption, null);
  assert.equal(parsed.items[1].transcript, null);
  assert.equal(parsed.items[1].representative_frame_timestamp_ms, null);
  assert.equal(parsed.items[1].embedding_ready, false);
});

test('장면 구간·순번·식별자·OCR·태그가 모순된 응답을 거부한다', () => {
  const invalidScenes = [
    [{ ...response.items[0], end_time_ms: 0 }, response.items[1]],
    [{ ...response.items[0], representative_frame_timestamp_ms: 4200 }, response.items[1]],
    [response.items[0], { ...response.items[1], scene_id: '41' }],
    [response.items[0], { ...response.items[1], scene_index: 1 }],
    [{ ...response.items[0], ocr_texts: ['서울역', '서울역'] }, response.items[1]],
    [
      {
        ...response.items[0],
        tags: [response.items[0].tags[0], response.items[0].tags[0]],
      },
      response.items[1],
    ],
  ];
  for (const items of invalidScenes) {
    assert.throws(() => parseClipAnalysisScenes({ ...response, items }));
  }
});

test('요약과 페이지 수치가 실제 페이지 계약과 어긋나면 거부한다', () => {
  for (const invalid of [
    { ...response, summary: { ...response.summary, captioned_scenes: 3 } },
    { ...response, summary: { ...response.summary, total_scenes: 3 } },
    { ...response, total_pages: 2 },
    { ...response, has_next: true },
    { ...response, size: 1 },
    { ...response, page: 1 },
  ]) {
    assert.throws(() => parseClipAnalysisScenes(invalid));
  }
});

test('알 수 없는 대사 출처·태그 유형·범위·검증 상태를 조용히 표시하지 않는다', () => {
  for (const patch of [
    { transcript: { text: '대사', source: 'uploaded' } },
    { tags: [{ ...response.items[0].tags[0], type: 'crowd_density' }] },
    { tags: [{ ...response.items[0].tags[0], scope: 'run' }] },
    { tags: [{ ...response.items[0].tags[0], verification: 'approved' }] },
  ]) {
    assert.throws(() =>
      parseClipAnalysisScenes({
        ...response,
        items: [{ ...response.items[0], ...patch }, response.items[1]],
      }),
    );
  }
});

test('장면 API는 clip·run·페이지를 경로와 쿼리에 싣고 세션과 취소 신호를 전달한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: '성공', data: response }),
  );
  const controller = new AbortController();

  const result = await getClipAnalysisScenes(
    response.clip_id,
    response.pipeline_run_id,
    0,
    20,
    controller.signal,
  );

  assert.equal(result.items.length, 2);
  const [urlValue, init] = fetch.mock.calls[0].arguments;
  const url = new URL(urlValue);
  assert.equal(url.pathname, `/api/v1/clips/${response.clip_id}/runs/32/scenes`);
  assert.equal(url.searchParams.get('page'), '0');
  assert.equal(url.searchParams.get('size'), '20');
  assert.equal(init.credentials, 'include');
  assert.equal(init.signal, controller.signal);
});

test('요청한 clip·run과 다른 응답 및 잘못된 요청 식별자를 거부한다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: '성공', data: response }),
  );
  await assert.rejects(getClipAnalysisScenes('21', '32', 0, 20));
  await assert.rejects(getClipAnalysisScenes(response.clip_id, '31', 0, 20));
  await assert.rejects(getClipAnalysisScenes('clip-demo', '32', 0, 20));
  await assert.rejects(getClipAnalysisScenes(response.clip_id, '0', 0, 20));
});
