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
  '@/features/wireframes/input-validation': './input-validation.ts',
  '@/features/wireframes/scene-download': './scene-download.ts',
  '@/features/wireframes/scene-preview-player': './scene-preview-player.tsx',
  '@/features/wireframes/scene-preview-media': './scene-preview-media.ts',
  '@/lib/api/client': '../../lib/api/client.ts',
  '@/lib/api/log': '../../lib/api/log.ts',
  '@/lib/api/error': '../../lib/api/error.ts',
  '@/lib/env': '../../lib/env.ts',
  '@/lib/auth/session-events': '../../lib/auth/session-events.ts',
  '@/features/wireframes/inquiry-state': './inquiry-state.ts',
  '@/components/api-error-notice': '../../components/api-error-notice.tsx',
  '@/features/wireframes/demo-scenes': './demo-scenes.ts',
  '@/features/wireframes/search-execution-status': './search-execution-status.ts',
  '@/features/wireframes/search-result-notices': './search-result-notices.tsx',
  '@/lib/api/error': '../../lib/api/error.ts',
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

const { InquiryDialog, ScenePreviewDialog } = await import('./scene-dialogs.tsx');
const { getDemoSearchExecution } = await import('./search-execution-status.ts');
const { results } = await import('./demo-scenes.ts');
const sceneDialogsSource = readFileSync(new URL('./scene-dialogs.tsx', import.meta.url), 'utf8');

function renderPreview({
  isSubmitted = false,
  isSubmitting = false,
  result = { ...results[0], searchResultId: '987' },
  scenes,
  state,
} = {}) {
  return renderToStaticMarkup(
    createElement(ScenePreviewDialog, {
      result,
      scenes,
      theme: 'shinhan',
      isSubmitted,
      isSubmitting,
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
  assert.ok(!html.includes('장면 다운로드'));
  assert.ok(!html.includes('원본 클립 다운로드'));
});

const { getSceneMediaUrl, getSceneThumbnailUrl, toScenePreviewMedia, formatMediaTime } =
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

test('저장된 장면과 원본 클립 다운로드를 함께 제공한다', () => {
  const html = renderPreview({
    result: {
      ...results[0],
      id: 3,
      sceneId: '9007199254740994',
      clipId: '9007199254740993',
    },
  });

  assert.ok(html.includes('장면 다운로드'));
  assert.ok(html.includes('원본 클립 다운로드'));
  assert.match(html, /aria-label="영상 다운로드" role="group"/);
  assert.match(sceneDialogsSource, /getSceneDownloadUrl\(result\.sceneId\)/);
  assert.doesNotMatch(sceneDialogsSource, /getSceneDownloadUrl\(result\.id\)/);
  assert.ok(html.includes('/api/v1/media/9007199254740993/download'));
});

test('장면과 원본 다운로드 노출은 서로의 ID에 의존하지 않는다', () => {
  const sceneOnly = renderPreview({
    result: { ...results[0], sceneId: '9007199254740994', clipId: undefined },
  });
  const clipOnly = renderPreview({
    result: { ...results[0], sceneId: undefined, clipId: '9007199254740993' },
  });

  assert.ok(sceneOnly.includes('장면 다운로드'));
  assert.ok(!sceneOnly.includes('원본 클립 다운로드'));
  assert.ok(!sceneOnly.includes('/api/v1/media/scenes/1/download'));
  assert.ok(!clipOnly.includes('장면 다운로드'));
  assert.ok(clipOnly.includes('원본 클립 다운로드'));
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

test('장면 ID로 대표 이미지 endpoint 경로를 조립한다', () => {
  assert.ok(
    getSceneThumbnailUrl('9007199254740993').endsWith('/api/v1/scenes/9007199254740993/thumbnail'),
  );
});

test('경로나 비정상 장면 ID를 thumbnail URL로 만들지 않는다', () => {
  for (const sceneId of [
    undefined,
    null,
    '',
    '0',
    '../21',
    '21/../22',
    'C:\\media\\21.jpg',
    'https://example.com/21',
    'scene_21',
  ]) {
    assert.equal(getSceneThumbnailUrl(sceneId), null);
  }
});

test('정상 Preview는 문의를 허용하고 공용 송출 전 고지를 표시한다', () => {
  const html = renderPreview();

  assert.match(html, /data-state="ready"/);
  assert.ok(html.includes('이상해요'));
  assert.ok(!html.includes('문의 불가'));
  assert.ok(html.includes('송출 전 최종 확인'));
});

test('Preview는 샷 유형만 표시하고 장면 유형은 표시하지 않는다', () => {
  const html = renderPreview({
    result: {
      ...results[0],
      shotType: '인터뷰',
      sceneType: '상세에 노출되면 안 되는 값',
    },
  });

  assert.ok(html.includes('샷 유형'));
  assert.ok(html.includes('인터뷰'));
  assert.ok(!html.includes('장면 유형'));
  assert.ok(!html.includes('상세에 노출되면 안 되는 값'));
});

test('데모와 유효하지 않은 결과 ID로는 문의를 접수할 수 없다', () => {
  for (const searchResultId of [undefined, null, '', '0', '-1', '1.5', 'scene-1']) {
    const html = renderPreview({ result: { ...results[0], searchResultId } });
    assert.match(html, /data-state="unavailable"/);
    assert.match(html, /저장된 검색 결과가 아니므로 문의할 수 없습니다/);
    assert.match(html, /aria-disabled="true"/);
  }
});

test('접수 완료와 snapshot 저장 실패 결과를 다른 상태로 표시한다', () => {
  const submittedHtml = renderPreview({ isSubmitted: true });
  // snapshot 저장이 실패한 실행의 결과는 search_result_id 가 없다(web-api §5.1). 그 결과만 문의 불가다.
  const unavailableHtml = renderPreview({
    state: 'degraded-snapshot',
    result: { ...results[0], searchResultId: null },
  });

  assert.match(submittedHtml, /data-state="submitted"/);
  assert.ok(submittedHtml.includes('접수됨'));
  assert.match(unavailableHtml, /data-state="unavailable"/);
  assert.ok(unavailableHtml.includes('문의 불가'));
  assert.ok(
    unavailableHtml.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'),
  );
  assert.match(unavailableHtml, /<button[^>]+aria-describedby="[^"]+"[^>]+aria-disabled="true"/);
  assert.match(unavailableHtml, /role="tooltip"/);
  assert.ok(unavailableHtml.includes('검색 기록 저장 실패'));
});

test('저장된 결과는 다른 페이지 snapshot 실패로 실행이 degraded여도 문의할 수 있다', () => {
  // 더보기로 이어 붙인 실행 상태가 degraded-snapshot 이어도, 이 결과 자신이 저장돼
  // search_result_id 가 있으면 문의할 수 있어야 한다 (S15P21A501-251 P1). 문의 가능 여부는
  // 전역 실행 상태가 아니라 선택한 결과의 저장 상태로 판단한다.
  const html = renderPreview({
    state: 'degraded-snapshot',
    result: { ...results[0], searchResultId: '987' },
  });

  assert.match(html, /data-state="ready"/);
  assert.ok(!html.includes('문의 불가'));
  assert.ok(!html.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'));
});

test('이미 접수된 문의는 snapshot 상태에서도 접수 완료로만 안내한다', () => {
  const html = renderPreview({ isSubmitted: true, state: 'degraded-snapshot' });

  assert.match(html, /data-state="submitted"/);
  assert.ok(html.includes('접수됨'));
  assert.ok(!html.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'));
});

test('Preview도 문의 제출 중 상태를 문구로 표시하고 재클릭을 막는다', () => {
  const html = renderPreview({ isSubmitting: true });

  assert.match(html, /data-state="submitting"/);
  assert.match(html, /aria-busy="true"/);
  assert.match(html, /disabled=""/);
  assert.ok(html.includes('접수 중'));
});

test('문의 다이얼로그는 설명 없이 제출할 수 있고 즉시 자동 개선되지 않음을 알린다', () => {
  const html = renderToStaticMarkup(
    createElement(InquiryDialog, {
      result: results[0],
      theme: 'shinhan',
      query: '귀성길 정체',
      onSubmit() {},
      onClose() {},
    }),
  );

  assert.ok(html.includes('설명 (선택)'));
  assert.ok(html.includes('비워두어도 접수할 수 있어요.'));
  assert.ok(html.includes('현재 검색 결과나 다른 검색은 즉시 변경되지 않습니다.'));
  assert.ok(html.includes('문의 접수'));
  assert.ok(!html.includes('required'));
});

test('제출 중에는 입력과 닫기·재제출을 잠그고 실패는 다시 시도로 표시한다', () => {
  const pendingHtml = renderToStaticMarkup(
    createElement(InquiryDialog, {
      result: results[0],
      theme: 'shinhan',
      query: '귀성길 정체',
      isSubmitting: true,
      onSubmit() {},
      onClose() {},
    }),
  );
  const failedHtml = renderToStaticMarkup(
    createElement(InquiryDialog, {
      result: results[0],
      theme: 'shinhan',
      query: '귀성길 정체',
      error: new Error('raw failure must not be exposed'),
      onSubmit() {},
      onClose() {},
    }),
  );

  assert.match(pendingHtml, /<form aria-busy="true"/);
  assert.ok((pendingHtml.match(/disabled=""/g) ?? []).length >= 3);
  assert.ok(pendingHtml.includes('접수 중'));
  assert.ok(failedHtml.includes('다시 시도'));
  assert.ok(failedHtml.includes('서버 응답을 확인할 수 없습니다.'));
  assert.ok(!failedHtml.includes('raw failure'));
  assert.ok(failedHtml.includes('role="alert"'));
});

test('구간 목록의 시각은 화면과 aria-label 모두 소수 초 없이 표시한다', () => {
  const scenes = [
    { ...results[0], id: 1, sceneStart: 12.345, sceneEnd: 20.6 },
    { ...results[1], id: 2, sceneStart: 73.5, sceneEnd: 80.25 },
  ];
  const html = renderPreview({ result: { ...scenes[0], searchResultId: '987' }, scenes });

  assert.ok(html.includes('00:12 – 00:20'));
  assert.ok(html.includes('01:13 – 01:20'));
  assert.match(html, /aria-label="구간 1: [^"]*, 00:12부터 00:20까지"/);
  assert.match(html, /aria-label="구간 2: [^"]*, 01:13부터 01:20까지"/);
  for (const decimal of ['12.345', '20.6', '73.5', '80.25']) {
    assert.ok(!html.includes(decimal));
  }
});
