import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import ts from 'typescript';

registerHooks({
  load(url, context, nextLoad) {
    if (url.endsWith('.ts') || url.endsWith('.tsx')) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const { loadSceneThumbnail } = await import('./scene-thumbnail.tsx');

const originalFetch = globalThis.fetch;

function stubFetch(handler) {
  globalThis.fetch = handler;
  return () => {
    globalThis.fetch = originalFetch;
  };
}

function failure(status, code) {
  return {
    ok: false,
    status,
    text: async () => JSON.stringify({ isSuccess: false, code, message: 'failed' }),
  };
}

test('장면 대표 이미지를 받으면 표시할 objectUrl을 돌려준다', async () => {
  const restore = stubFetch(async () => ({
    ok: true,
    status: 200,
    blob: async () => new Blob(['x']),
  }));
  try {
    const result = await loadSceneThumbnail(
      '/api/v1/scenes/21/thumbnail',
      new AbortController().signal,
    );
    assert.ok('objectUrl' in result);
    assert.match(result.objectUrl, /^blob:/);
    URL.revokeObjectURL(result.objectUrl);
  } finally {
    restore();
  }
});

test('이미지 성공과 JSON 실패 응답을 모두 받을 수 있게 요청한다', async () => {
  let requestedAccept;
  const restore = stubFetch(async (_src, options) => {
    requestedAccept = options.headers.accept;
    return failure(404, 'SCENE_404_003');
  });
  try {
    await loadSceneThumbnail('/api/v1/scenes/21/thumbnail', new AbortController().signal);
    assert.equal(requestedAccept, 'image/*, application/json');
  } finally {
    restore();
  }
});

test('이미지 없음과 파일 누락은 서로 다른 대체 안내를 준다', async () => {
  const cases = [
    ['SCENE_404_002', '대표 이미지를 아직 준비하고 있습니다.'],
    ['SCENE_404_003', '대표 이미지 파일을 찾을 수 없습니다.'],
    ['SCENE_404_001', '장면 정보를 찾을 수 없습니다.'],
    ['SCENE_503_001', '대표 이미지를 불러오지 못했습니다.'],
  ];
  for (const [code, message] of cases) {
    const restore = stubFetch(async () => failure(code.startsWith('SCENE_404') ? 404 : 503, code));
    try {
      const result = await loadSceneThumbnail(
        '/api/v1/scenes/21/thumbnail',
        new AbortController().signal,
      );
      assert.deepEqual(result, { message });
    } finally {
      restore();
    }
  }
});

test('실패 envelope가 아니거나 요청 자체가 실패해도 대체 안내로 끝난다', async () => {
  const restore = stubFetch(async () => {
    throw new TypeError('network down');
  });
  try {
    const result = await loadSceneThumbnail(
      '/api/v1/scenes/21/thumbnail',
      new AbortController().signal,
    );
    assert.deepEqual(result, { message: '대표 이미지를 불러오지 못했습니다.' });
  } finally {
    restore();
  }
});

test('원본 해상도 이미지를 4장 넘게 동시에 요청하지 않는다', async () => {
  const pending = [];
  const restore = stubFetch(
    () =>
      new Promise((resolve) => {
        pending.push(() => resolve({ ok: true, status: 200, blob: async () => new Blob(['x']) }));
      }),
  );
  try {
    const signal = new AbortController().signal;
    const loads = Array.from({ length: 6 }, () =>
      loadSceneThumbnail('/api/v1/scenes/21/thumbnail', signal),
    );

    await new Promise(setImmediate);
    assert.equal(pending.length, 4);

    pending[0]();
    await new Promise(setImmediate);
    assert.equal(pending.length, 5);

    for (let index = 1; index < 6; index += 1) {
      while (pending.length <= index) await new Promise(setImmediate);
      pending[index]();
    }
    for (const result of await Promise.all(loads)) {
      if ('objectUrl' in result) URL.revokeObjectURL(result.objectUrl);
    }
    assert.equal(pending.length, 6);
  } finally {
    restore();
  }
});
