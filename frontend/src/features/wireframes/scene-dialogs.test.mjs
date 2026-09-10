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

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (
      specifier === '@/features/wireframes/wireframe.module.css' ||
      specifier === '@/features/wireframes/shinhan-search.module.css'
    ) {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (specifier === '@/features/wireframes/demo-scenes') {
      return { url: new URL('./demo-scenes.ts', import.meta.url).href, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url === new URL('./scene-dialogs.tsx', import.meta.url).href) {
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
const { results } = await import('./demo-scenes.ts');

function renderScene(result, showSafetyNotice) {
  return renderToStaticMarkup(
    createElement(ScenePreviewDialog, {
      result,
      theme: 'shinhan',
      onClose() {},
      showSafetyNotice,
    }),
  );
}

test('출처 접두사는 matchEvidence가 있는 결과에만 표시한다', () => {
  const withEvidence = renderScene(results[0]);
  const withoutEvidence = renderScene({
    ...results[0],
    matchEvidence: undefined,
    source: '장소가 정확한지 아직 확인되지 않았어요.',
  });

  assert.ok(withEvidence.includes('출처 · Keyframe OCR'));
  assert.ok(withoutEvidence.includes('장소가 정확한지 아직 확인되지 않았어요.'));
  assert.ok(!withoutEvidence.includes('출처 · 장소가 정확한지 아직 확인되지 않았어요.'));
});

test('검색 결과 Preview에서는 송출 전 최종 확인 문구를 숨길 수 있다', () => {
  const withNotice = renderScene(results[0]);
  const withoutNotice = renderScene(results[0], false);

  assert.ok(withNotice.includes('송출 전 최종 확인'));
  assert.ok(withNotice.includes('내용·최신성·권리·사용 적합성을 확인하세요.'));
  assert.ok(!withoutNotice.includes('송출 전 최종 확인'));
  assert.ok(!withoutNotice.includes('내용·최신성·권리·사용 적합성을 확인하세요.'));
});
