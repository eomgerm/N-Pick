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

function renderPreview({
  isSubmitted = false,
  isSubmitting = false,
  result = results[0],
  state,
} = {}) {
  return renderToStaticMarkup(
    createElement(ScenePreviewDialog, {
      result,
      theme: 'shinhan',
      isSubmitted,
      isSubmitting,
      onInquiry() {},
      onClose() {},
      keepLoading: true,
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
