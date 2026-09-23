import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import ts from 'typescript';

registerHooks({
  load(url, context, nextLoad) {
    if (url.endsWith('.ts')) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const {
  checkClipDownload,
  ClipDownloadError,
  fetchSceneDownload,
  readDownloadFileName,
  SceneDownloadError,
} = await import('./scene-download.ts');

test('장면 영상과 UTF-8 파일명을 byte 응답에서 읽는다', async (t) => {
  t.mock.method(
    globalThis,
    'fetch',
    async () =>
      new Response(new Blob(['scene-bytes'], { type: 'video/mp4' }), {
        status: 200,
        headers: {
          'content-disposition': "attachment; filename*=UTF-8''scene-%ED%95%9C%EA%B8%80.mp4",
        },
      }),
  );

  const result = await fetchSceneDownload('/api/v1/media/scenes/77/download');

  assert.equal(result.blob.size, 11);
  assert.equal(result.fileName, 'scene-한글.mp4');
});

test('추출 포화 응답은 JSON을 저장하지 않고 재시도 안내로 바꾼다', async (t) => {
  t.mock.method(
    globalThis,
    'fetch',
    async () =>
      new Response(JSON.stringify({ code: 'CLIP_503_012', message: 'internal detail' }), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      }),
  );

  await assert.rejects(
    fetchSceneDownload('/api/v1/media/scenes/77/download'),
    (error) =>
      error instanceof SceneDownloadError &&
      error.message ===
        '다른 장면을 준비 중이거나 장면 추출에 실패했습니다. 잠시 후 다시 시도해 주세요.',
  );
});

test('경로 문자가 든 서버 파일명은 다운로드 이름으로 사용하지 않는다', () => {
  assert.equal(readDownloadFileName('attachment; filename="../private.mp4"'), 'private.mp4');
  assert.equal(readDownloadFileName("attachment; filename*=UTF-8''bad%0Aname.mp4"), 'badname.mp4');
});

test('원본 클립은 본문을 읽지 않는 HEAD로 다운로드 가능 여부를 확인한다', async (t) => {
  t.mock.method(globalThis, 'fetch', async (_url, init) => {
    assert.equal(init.method, 'HEAD');
    assert.equal(init.credentials, 'include');
    return new Response(null, { status: 200 });
  });

  await checkClipDownload('/api/v1/media/77/download');
});

test('원본 클립 확인 실패를 상태에 맞는 한국어 안내로 바꾼다', async (t) => {
  let status = 401;
  t.mock.method(globalThis, 'fetch', async () => new Response(null, { status }));

  for (const [responseStatus, message] of [
    [401, '로그인이 만료되었습니다. 다시 로그인한 뒤 시도해 주세요.'],
    [403, '원본 클립을 다운로드할 권한이 없습니다.'],
    [404, '원본 영상 파일을 찾을 수 없습니다.'],
    [503, '영상 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'],
  ]) {
    status = responseStatus;
    await assert.rejects(
      checkClipDownload('/api/v1/media/77/download'),
      (error) => error instanceof ClipDownloadError && error.message === message,
    );
  }
});
