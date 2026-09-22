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
const { getProcessingClips, getProcessingClip, parseClipSummary, parseClipDetail, parseClipPage } =
  await import('./clip-processing-api.ts');
const {
  clipListPollInterval,
  clipDetailPollInterval,
  processingProgressLabel,
  processingAsrStatusLabel,
  processingRefreshState,
  processingTranscriptLabel,
  stageStatusLabels,
} = await import('./clip-processing-view.ts');

const clip = {
  clip_id: '9223372036854775807',
  title: null,
  source_type: 'archive',
  search_available: true,
  active_pipeline_run_id: '31',
  created_at: '2026-09-16T01:00:00Z',
  updated_at: '2026-09-16T02:00:00Z',
  latest_run: {
    pipeline_run_id: '32',
    processing_no: 2,
    status: 'failed',
    error_code: 'STAGE_TIMEOUT',
    created_at: '2026-09-16T01:00:00Z',
    started_at: null,
    finished_at: '2026-09-16T02:00:00Z',
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
const page = {
  items: [clip],
  page: 0,
  size: 10,
  total_elements: 1,
  total_pages: 1,
  has_next: false,
  run_counts: { queued: 0, running: 0, failed: 1, succeeded: 24, no_run: 0 },
};
const detail = {
  clip,
  default_transcript_source: 'provided',
  has_subtitle: true,
  has_script: false,
  processing_details: {
    pipeline_run_id: '32',
    record_status: 'partial',
    stages: [],
    failed_stages: [],
    missing_channels: null,
    retryable: null,
    transcript: null,
  },
};

test('최신 실패와 이전 검색 제공을 분리하고 bigint·부분 기록의 null을 보존한다', () => {
  const parsed = parseClipSummary(clip);
  assert.equal(parsed.clip_id, '9223372036854775807');
  assert.equal(parsed.latest_run.status, 'failed');
  assert.equal(parsed.search_available, true);
  assert.equal(parsed.progress.total_steps, null);
  assert.equal(processingProgressLabel(parsed.progress), '일부 처리 기록만 확인됨');
  assert.equal(parseClipPage(page).run_counts.succeeded, 24);
  assert.equal(parseClipDetail(detail).processing_details.retryable, null);
});

test('실행 없음과 기록의 실제 0을 구분하고 이전 형식의 완전한 기록도 읽는다', () => {
  const noRun = parseClipSummary({ ...clip, latest_run: null, progress: null });
  assert.equal(noRun.progress, null);
  for (const record_status of ['available', 'legacy']) {
    const parsed = parseClipSummary({
      ...clip,
      progress: {
        record_status,
        current_stage: null,
        total_steps: 10,
        succeeded_steps: 0,
        skipped_steps: 0,
        failed_steps: 0,
      },
    });
    assert.match(processingProgressLabel(parsed.progress), /전체 10단계 · 성공 0/);
  }
});

test('부분 기록의 추정 숫자와 서로 다른 처리 시도의 상세 혼합을 거부한다', () => {
  assert.throws(() =>
    parseClipSummary({ ...clip, progress: { ...clip.progress, total_steps: 10 } }),
  );
  assert.throws(() => parseClipSummary({ ...clip, search_available: false }));
  assert.throws(() =>
    parseClipDetail({
      ...detail,
      processing_details: { ...detail.processing_details, pipeline_run_id: '31' },
    }),
  );
  assert.throws(() => parseClipPage({ ...page, items: [clip, clip] }));
});

test('자동 재시도 이력과 수동 재처리 가능 여부를 분리한다', () => {
  const parsed = parseClipDetail({
    ...detail,
    processing_details: {
      ...detail.processing_details,
      stages: [
        {
          name: 'asr',
          status: 'pending',
          attempts: 1,
          max_attempts: 3,
          automatic_retryable: true,
          started_at: null,
          finished_at: null,
          error_code: null,
          reason_code: null,
          failed_attempts: [{ attempt: 1, error_code: 'STAGE_TIMEOUT', finished_at: null }],
        },
      ],
    },
  });
  const stage = parsed.processing_details.stages[0];
  assert.equal(stage.automatic_retryable, true);
  assert.equal(stage.failed_attempts[0].error_code, 'STAGE_TIMEOUT');
  assert.equal(parsed.processing_details.retryable, null);
});

test('영상 조회 계약은 활성 처리 ID 유무와 검색 제공 여부의 동치를 보장한다', () => {
  const unavailable = {
    ...clip,
    clip_id: '21',
    search_available: false,
    active_pipeline_run_id: null,
    latest_run: null,
    progress: null,
  };
  const parsed = parseClipPage({
    ...page,
    size: 10,
    total_elements: 2,
    items: [clip, unavailable],
  });
  assert.equal(parsed.items.length, 2);
  assert.equal(parsed.items[0].search_available, true);
  assert.equal(parsed.items[1].search_available, false);
  for (const invalid of [
    { ...clip, search_available: false },
    { ...unavailable, search_available: true },
  ]) {
    assert.throws(() => parseClipSummary(invalid));
    assert.throws(() => parseClipPage({ ...page, items: [invalid] }));
  }
});

test('요약은 등록자 로그인 ID 만 읽고 내부 식별자는 받지 않는다', () => {
  const named = parseClipSummary({ ...clip, registered_by: { login_id: 'arch04' } });
  assert.equal(named.registered_by.login_id, 'arch04');
  assert.equal(parseClipSummary({ ...clip, registered_by: null }).registered_by, null);
  for (const invalid of [{ login_id: 42 }, { login_id: '' }, {}]) {
    assert.throws(() => parseClipSummary({ ...clip, registered_by: invalid }));
  }
});

test('목록은 칩이 고른 상태와 내 영상 여부·0 기반 페이지·취소 신호·세션을 전달한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: '성공', data: page }),
  );
  const controller = new AbortController();
  await getProcessingClips(2, ['failed', 'no_run'], true, controller.signal);
  const [attention] = fetch.mock.calls.map((call) => call.arguments);
  const url = new URL(attention[0]);
  assert.equal(url.searchParams.get('status'), 'failed,no_run');
  assert.equal(url.searchParams.get('mine'), 'true');
  assert.equal(url.searchParams.get('page'), '2');
  assert.equal(url.searchParams.get('size'), '10');
  assert.equal(attention[1].credentials, 'include');
  assert.equal(attention[1].signal, controller.signal);
});

test('전체 칩이고 내 영상이 아니면 두 파라미터를 아예 보내지 않는다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: '성공', data: page }),
  );
  await getProcessingClips(0, [], false);
  const url = new URL(fetch.mock.calls[0].arguments[0]);
  assert.equal(url.searchParams.get('status'), null);
  assert.equal(url.searchParams.get('mine'), null);
  assert.equal(url.searchParams.get('page'), '0');
});

test('다른 영상 응답·조회 오류를 데모로 대체하지 않는다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ isSuccess: true, code: 'COMM_200', message: '성공', data: detail }),
  );
  assert.equal((await getProcessingClip(clip.clip_id)).clip.clip_id, clip.clip_id);
  await assert.rejects(getProcessingClip('21'));
  await assert.rejects(getProcessingClip('clip-demo'));
});

test('실제 실행 중에만 polling하고 완료·실패·미확인·오류에서 중단한다', () => {
  for (const status of ['queued', 'running']) assert.equal(clipDetailPollInterval(status), 5000);
  for (const status of ['succeeded', 'failed', undefined])
    assert.equal(clipDetailPollInterval(status), false);
  assert.equal(clipDetailPollInterval('running', true), false);
  assert.equal(clipListPollInterval({ queued: 1, running: 0 }), 5000);
  assert.equal(clipListPollInterval({ queued: 0, running: 0 }), false);
  assert.equal(clipListPollInterval({ queued: 1, running: 2 }, true), false);
  assert.equal(clipListPollInterval(undefined), false);
});

test('최근 등록의 실행 없음은 1분 동안 재조회하되 오래된 기록·조회 전·오류는 반복하지 않는다', () => {
  const createdAt = '2026-09-17T01:00:00Z';
  const createdMs = Date.parse(createdAt);
  assert.equal(clipDetailPollInterval(null, false, createdAt, createdMs), 5000);
  assert.equal(clipDetailPollInterval(null, false, createdAt, createdMs + 59_999), 5000);
  assert.equal(clipDetailPollInterval(null, false, createdAt, createdMs + 60_000), false);
  assert.equal(clipDetailPollInterval(null, false, createdAt, createdMs + 86_400_000), false);
  assert.equal(clipDetailPollInterval(null, true, createdAt, createdMs), false);
  assert.equal(clipDetailPollInterval(undefined, false, createdAt, createdMs), false);
  assert.equal(clipDetailPollInterval(null), false);
  assert.equal(clipDetailPollInterval(null, false, createdAt, createdMs - 1), false);
  assert.equal(clipDetailPollInterval(null, false, 'invalid', createdMs), false);
  assert.equal(clipDetailPollInterval('queued', false, createdAt, createdMs + 60_000), 5000);
  assert.equal(clipDetailPollInterval('running', false, createdAt, createdMs + 60_000), 5000);
  for (const status of ['succeeded', 'failed']) {
    assert.equal(clipDetailPollInterval(status, false, createdAt, createdMs), false);
  }
});

function asrDetail(asrStatus, stageStatus = 'pending', runStatus = 'running') {
  return {
    ...detail,
    clip: { ...clip, latest_run: { ...clip.latest_run, status: runStatus } },
    processing_details: {
      ...detail.processing_details,
      stages: [{ name: 'asr', status: stageStatus }],
      transcript: { record_status: 'unavailable', asr_status: asrStatus, asr_required: false },
    },
  };
}

test('ASR는 대사 출처 기록 유무와 관계없이 저장 상태를 우선하며 전체 실행 상태로 덮어쓰지 않는다', () => {
  const labels = {
    pending: '대기',
    running: '처리 중',
    succeeded: '완료',
    failed: '실패',
    skipped: '생략',
  };
  for (const [status, label] of Object.entries(labels)) {
    for (const runStatus of ['queued', 'running', 'succeeded', 'failed']) {
      assert.equal(processingAsrStatusLabel(asrDetail(status, 'unknown', runStatus)), label);
    }
  }
  assert.equal(processingAsrStatusLabel(asrDetail('failed', 'succeeded', 'succeeded')), '실패');
  assert.equal(processingAsrStatusLabel(asrDetail('skipped', 'running', 'running')), '생략');
  assert.equal(stageStatusLabels.running, '진행 중');
  assert.equal(processingTranscriptLabel('succeeded'), '성공');
  assert.equal(processingTranscriptLabel(null), '미확인');
});

test('ASR 미확인 값은 동일 실행의 ASR 단계로만 보완하고 근거가 없으면 그대로 구분한다', () => {
  for (const status of [null, 'unknown']) {
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'pending')), '대기');
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'running')), '처리 중');
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'succeeded')), '완료');
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'failed')), '실패');
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'skipped')), '생략');
    assert.equal(processingAsrStatusLabel(asrDetail(status, 'unknown')), '상태 정보 없음');
  }
  const missingTranscript = asrDetail(null, 'pending');
  missingTranscript.processing_details.transcript = null;
  assert.equal(processingAsrStatusLabel(missingTranscript), '대기');
  missingTranscript.processing_details.stages = [{ name: 'ocr', status: 'running' }];
  assert.equal(processingAsrStatusLabel(missingTranscript), '상태 정보 없음');
  const mismatched = asrDetail('running', 'succeeded');
  mismatched.processing_details.pipeline_run_id = '31';
  assert.equal(processingAsrStatusLabel(mismatched), '상태 정보 없음');
  assert.equal(processingAsrStatusLabel({ ...detail, processing_details: null }), '상태 정보 없음');
  assert.equal(processingAsrStatusLabel(undefined), '상태 정보 없음');
});

test('자동 확인 안내는 조회 중·중단·오프라인·화면 중지 상태와 수동 복구를 구분한다', () => {
  const state = {
    canPoll: true,
    hasError: false,
    fetchStatus: 'idle',
    isOnline: true,
    isFocused: true,
  };
  assert.deepEqual(processingRefreshState(state), { mode: 'automatic', isAutomatic: true });
  assert.deepEqual(processingRefreshState({ ...state, fetchStatus: 'fetching' }), {
    mode: 'refreshing',
    isAutomatic: true,
  });
  for (const patch of [{ fetchStatus: 'paused' }, { isOnline: false }, { isFocused: false }]) {
    assert.deepEqual(processingRefreshState({ ...state, ...patch }), {
      mode: 'paused',
      isAutomatic: false,
    });
  }
  assert.deepEqual(processingRefreshState({ ...state, hasError: true }), {
    mode: 'error',
    isAutomatic: false,
  });
  assert.deepEqual(processingRefreshState({ ...state, canPoll: false }), {
    mode: 'manual',
    isAutomatic: false,
  });
  assert.deepEqual(
    processingRefreshState({ ...state, canPoll: false, fetchStatus: 'paused', isOnline: false }),
    { mode: 'manual', isAutomatic: false },
  );
  assert.deepEqual(
    processingRefreshState({ ...state, canPoll: false, hasError: true, fetchStatus: 'paused' }),
    { mode: 'error', isAutomatic: false },
  );
  assert.deepEqual(processingRefreshState({ ...state, hasError: true, fetchStatus: 'fetching' }), {
    mode: 'refreshing',
    isAutomatic: false,
  });
});
