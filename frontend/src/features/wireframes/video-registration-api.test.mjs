import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

import { ApiClientError } from '../../lib/api/error.ts';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice('@/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const {
  createClipRegistrationFormData,
  createClipRegistrationSubmission,
  getClipRegistrationErrorPresentation,
  parseClipRegistrationResponse,
  registerClip,
} = await import('./video-registration-api.ts');

function createSnapshot(overrides = {}) {
  return {
    video: new File(['video'], '뉴스.mp4', { type: 'video/mp4' }),
    sourceType: 'broadcast',
    title: '저녁 뉴스',
    broadcastDate: '2026-09-08',
    filmedDate: '2026-09-07',
    subtitle: new File(['WEBVTT'], '자막.vtt'),
    script: new File(['일반 대본'], '대본.txt', { type: 'text/plain' }),
    rightsConfirmed: true,
    externalProcessingConfirmed: true,
    ...overrides,
  };
}

test('방송분 요청은 snake_case 필드와 UTF-8 대본을 담고 매번 새 FormData를 만든다', async () => {
  const snapshot = createSnapshot();
  const first = await createClipRegistrationFormData(snapshot);
  const second = await createClipRegistrationFormData(snapshot);

  assert.notStrictEqual(first, second);
  assert.deepEqual(
    [...first.keys()],
    [
      'video',
      'source_type',
      'title',
      'broadcast_date',
      'filmed_date',
      'subtitle',
      'script_text',
      'rights_confirmed',
      'external_processing_confirmed',
    ],
  );
  assert.equal(first.get('source_type'), 'broadcast');
  assert.equal(first.get('broadcast_date'), '2026-09-08');
  assert.equal(first.get('filmed_date'), '2026-09-07');
  assert.equal(first.get('script_text'), '일반 대본');
  assert.equal(first.get('rights_confirmed'), 'true');
  assert.equal(first.get('external_processing_confirmed'), 'true');
});

test('자료 영상은 방송일을 절대 전송하지 않고 빈 선택값은 생략한다', async () => {
  const body = await createClipRegistrationFormData(
    createSnapshot({
      sourceType: 'archive',
      title: '',
      broadcastDate: '2026-09-08',
      filmedDate: '',
      subtitle: null,
      script: null,
    }),
  );
  assert.deepEqual(
    [...body.keys()],
    ['video', 'source_type', 'rights_confirmed', 'external_processing_confirmed'],
  );
  assert.equal(body.get('source_type'), 'archive');
  assert.equal(body.has('broadcast_date'), false);
  assert.equal(body.has('filmed_date'), false);
});

test('일반 대본은 잘못된 UTF-8 바이트를 대체 문자로 보내지 않고 거절한다', async () => {
  const snapshot = createSnapshot({
    script: new File([Uint8Array.of(0xff, 0xfe)], '잘못된대본.txt'),
  });
  await assert.rejects(() => createClipRegistrationFormData(snapshot), {
    name: 'ScriptTextDecodeError',
  });
});

test('성공 응답은 문자열 영상·처리 ID와 queued만 허용한다', () => {
  assert.deepEqual(
    parseClipRegistrationResponse({
      clip_id: '398021840012345',
      pipeline_run_id: '398021847361024',
      status: 'queued',
    }),
    {
      clipId: '398021840012345',
      pipelineRunId: '398021847361024',
      status: 'queued',
    },
  );
  for (const response of [
    null,
    { clip_id: 398021840012345, pipeline_run_id: '2', status: 'queued' },
    { clip_id: '1', pipeline_run_id: '', status: 'queued' },
    { clip_id: ' 1', pipeline_run_id: '2', status: 'queued' },
    { clip_id: 'undefined', pipeline_run_id: '2', status: 'queued' },
    { clip_id: '1', pipeline_run_id: '2', status: 'running' },
  ]) {
    assert.throws(() => parseClipRegistrationResponse(response), ApiClientError);
  }
});

test('허용한 validation 필드의 안전한 한국어 문자열만 인라인 오류로 읽는다', () => {
  const error = new ApiClientError('api', 400, {
    code: 'COMM_400_001',
    message: 'Request validation failed',
    diagnostics: {
      response: {
        data: {
          title: '제목은 500자 이내로 입력해 주세요.',
          video: 'C:\\server\\secret.mp4',
          unknownField: '알 수 없는 필드는 노출하지 않습니다.',
        },
      },
    },
  });
  assert.deepEqual(getClipRegistrationErrorPresentation(error), {
    fieldErrors: { title: '제목은 500자 이내로 입력해 주세요.' },
    retryMode: 'none',
    showGlobal: true,
  });
});

test('403 보안 실패는 입력 변경 없이 같은 요청으로 수동 재시도한다', () => {
  const error = new ApiClientError('api', 403, {
    code: 'COMM_403',
    message: 'Access is denied',
  });

  assert.deepEqual(getClipRegistrationErrorPresentation(error), {
    fieldErrors: {},
    retryMode: 'same-request',
    showGlobal: true,
  });
});

test('등록 API는 POST multipart와 멱등성 키, abort signal을 공통 client에 전달한다', async (t) => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (input, init) => {
    request = { input, init };
    return new Response(
      JSON.stringify({
        isSuccess: true,
        code: 'CLIP_201',
        message: '영상 등록을 접수했습니다.',
        data: { clip_id: '11', pipeline_run_id: '12', status: 'queued' },
      }),
      { status: 201, headers: { 'content-type': 'application/json' } },
    );
  };
  t.after(() => {
    globalThis.fetch = originalFetch;
  });

  const signal = new AbortController().signal;
  const result = await registerClip(
    createClipRegistrationSubmission(
      createSnapshot({ subtitle: null, script: null }),
      () => 'key-124',
    ),
    signal,
  );

  assert.deepEqual(result, { clipId: '11', pipelineRunId: '12', status: 'queued' });
  assert.equal(request.input, 'http://127.0.0.1:8080/api/v1/clips');
  assert.equal(request.init.method, 'POST');
  assert.equal(new Headers(request.init.headers).get('Idempotency-Key'), 'key-124');
  assert.strictEqual(request.init.signal, signal);
  assert.ok(request.init.body instanceof FormData);
});

test('업무 오류는 지정된 입력에 매핑하고 모호한 실패와 새 요청을 구분한다', () => {
  const archiveDate = new ApiClientError('api', 400, {
    code: 'CLIP_400_003',
    message: '자료 영상에는 방송일을 입력할 수 없습니다.',
  });
  assert.equal(
    getClipRegistrationErrorPresentation(archiveDate).fieldErrors.sourceType,
    archiveDate.message,
  );

  const sameRequestErrors = [
    new ApiClientError('network', 0),
    new ApiClientError('aborted', 0),
    new ApiClientError('invalid-response', 201),
    new ApiClientError('http', 500),
    new ApiClientError('api', 409, {
      code: 'CLIP_409_002',
      message: '영상 등록을 처리 중입니다. 같은 요청으로 다시 확인해 주세요.',
    }),
    new ApiClientError('api', 503, {
      code: 'CLIP_503_008',
      message: '등록 결과를 확인하지 못했습니다. 같은 요청으로 결과를 확인해 주세요.',
    }),
  ];
  for (const error of sameRequestErrors) {
    assert.equal(getClipRegistrationErrorPresentation(error).retryMode, 'same-request');
  }
  for (const code of ['CLIP_409_001', 'CLIP_409_003']) {
    assert.equal(
      getClipRegistrationErrorPresentation(new ApiClientError('api', 409, { code })).retryMode,
      'new-request',
    );
  }
});

test('같은 논리 요청은 snapshot/key를 재사용하고 입력 변경 뒤에는 새 key를 만든다', () => {
  const first = createClipRegistrationSubmission(createSnapshot(), () => 'key-one');
  const retry = first;
  const edited = createClipRegistrationSubmission(
    createSnapshot({ title: '수정 제목' }),
    () => 'key-two',
  );

  assert.strictEqual(retry.snapshot, first.snapshot);
  assert.equal(retry.key, first.key);
  assert.equal(Object.isFrozen(first.snapshot), true);
  assert.notEqual(edited.key, first.key);
  assert.notStrictEqual(edited.snapshot, first.snapshot);
});
