import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const cssModuleUrl = `data:text/javascript,${encodeURIComponent(
  'export default new Proxy({}, { get: (_, key) => String(key) });',
)}`;
const localFiles = {
  '@/features/wireframes/scene-preview-player': './scene-preview-player.tsx',
  '@/features/wireframes/scene-preview-media': './scene-preview-media.ts',
  '@/lib/api/client': '../../lib/api/client.ts',
  '@/lib/api/error': '../../lib/api/error.ts',
  '@/lib/env': '../../lib/env.ts',
  '@/lib/auth/session-events': '../../lib/auth/session-events.ts',
  '@/features/wireframes/inquiry-state': './inquiry-state.ts',
  '@/features/wireframes/demo-scenes': './demo-scenes.ts',
  '@/features/wireframes/search-execution-status': './search-execution-status.ts',
  '@/features/wireframes/search-result-notices': './search-result-notices.tsx',
};

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (
      specifier === '@/features/wireframes/wireframe.module.css' ||
      specifier === '@/features/wireframes/shinhan-search.module.css'
    ) {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (localFiles[specifier]) {
      return { url: new URL(localFiles[specifier], import.meta.url).href, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
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

const { ScenePreviewDialog } = await import('./scene-dialogs.tsx');
const { getDemoSearchExecution } = await import('./search-execution-status.ts');
const { results } = await import('./demo-scenes.ts');

function renderPreview({ isSubmitted = false, result = results[0], state } = {}) {
  return renderToStaticMarkup(
    createElement(ScenePreviewDialog, {
      result,
      theme: 'shinhan',
      isSubmitted,
      onInquiry() {},
      onClose() {},
      searchExecution: getDemoSearchExecution(state),
    }),
  );
}

test('출처 접두사는 matchEvidence가 있는 결과에만 표시한다', () => {
  const withEvidence = renderPreview();
  const withoutEvidence = renderPreview({
    result: {
      ...results[0],
      matchEvidence: undefined,
      source: '장소가 정확한지 아직 확인되지 않았어요.',
    },
  });

  assert.ok(withEvidence.includes('출처 · Keyframe OCR'));
  assert.ok(withoutEvidence.includes('장소가 정확한지 아직 확인되지 않았어요.'));
  assert.ok(!withoutEvidence.includes('출처 · 장소가 정확한지 아직 확인되지 않았어요.'));
});

test('ID 없는 데모는 미디어를 요청하거나 재생 중으로 표시하지 않는다', () => {
  const html = renderPreview();
  assert.ok(html.includes('영상 ID 또는 장면 구간을 확인할 수 없어 재생할 수 없습니다.'));
  assert.ok(!html.includes('<video'));
  assert.ok(!html.includes('재생 중'));
});

const { getSceneMediaUrl, toScenePreviewMedia, formatMediaTime } =
  await import('./scene-preview-media.ts');

test('큰 clip ID를 보존하고 밀리초를 초로 변환한다', () => {
  const media = toScenePreviewMedia({
    clipId: '9007199254740993',
    startTimeMs: 1250,
    endTimeMs: 2700,
  });
  assert.deepEqual(media, { clipId: '9007199254740993', sceneStart: 1.25, sceneEnd: 2.7 });
  assert.ok(getSceneMediaUrl(media).endsWith('/api/v1/media/9007199254740993'));
  assert.equal(formatMediaTime(3661.25), '1:01:01');
});

test('경로·다른 ID·비정상 구간을 media URL로 만들지 않는다', () => {
  for (const clipId of [
    undefined,
    '',
    '0',
    '../21',
    'C:\\media\\21.mp4',
    'https://example.com/21',
    'scene_21',
  ]) {
    assert.equal(getSceneMediaUrl({ clipId, sceneStart: 0, sceneEnd: 1 }), null);
  }
  for (const [sceneStart, sceneEnd] of [
    [-1, 1],
    [2, 1],
    [1, 1],
    [NaN, 1],
    [0, Infinity],
  ]) {
    assert.equal(getSceneMediaUrl({ clipId: '21', sceneStart, sceneEnd }), null);
  }
});

test('정상 Preview는 문의를 허용하고 공용 송출 전 고지를 표시한다', () => {
  const html = renderPreview();

  assert.match(html, /data-state="ready"/);
  assert.ok(html.includes('이상해요'));
  assert.ok(!html.includes('문의 불가'));
  assert.ok(html.includes('송출 전 최종 확인'));
});

test('접수 완료와 snapshot 문의 불가를 다른 상태로 표시한다', () => {
  const submittedHtml = renderPreview({ isSubmitted: true });
  const unavailableHtml = renderPreview({ state: 'degraded-snapshot' });

  assert.match(submittedHtml, /data-state="submitted"/);
  assert.ok(submittedHtml.includes('접수됨'));
  assert.match(unavailableHtml, /data-state="unavailable"/);
  assert.ok(unavailableHtml.includes('문의 불가'));
  assert.ok(
    unavailableHtml.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'),
  );
  assert.match(unavailableHtml, /<dialog aria-describedby="[^"]+"/);
  assert.match(unavailableHtml, /disabled=""/);
  assert.ok(unavailableHtml.includes('검색 기록 저장 실패'));
});

test('이미 접수된 문의는 snapshot 상태에서도 접수 완료로만 안내한다', () => {
  const html = renderPreview({ isSubmitted: true, state: 'degraded-snapshot' });

  assert.match(html, /data-state="submitted"/);
  assert.ok(html.includes('접수됨'));
  assert.ok(!html.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'));
});
