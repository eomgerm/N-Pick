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
    if (specifier === '@/features/wireframes/wireframe.module.css') {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (specifier === '@/features/wireframes/search-execution-status') {
      return {
        url: new URL('./search-execution-status.ts', import.meta.url).href,
        shortCircuit: true,
      };
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url.endsWith('/search-result-notices.tsx') || url.endsWith('/search-execution-status.ts')) {
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

const { SearchResultNotices } = await import('./search-result-notices.tsx');
const { getDemoSearchExecution } = await import('./search-execution-status.ts');

function renderNotices(state, variant = 'results') {
  return renderToStaticMarkup(
    createElement(SearchResultNotices, {
      execution: getDemoSearchExecution(state),
      variant,
    }),
  );
}

test('알릴 것이 없으면 아무 고지도 렌더하지 않는다', () => {
  const html = renderNotices();

  assert.ok(!html.includes('일부 기능 누락'));
  assert.ok(!html.includes('검수 규칙 적용'));
});

test('세 degraded 사유를 서로 다른 사용자 문구로 표시한다', () => {
  const cases = [
    ['degraded-resolver', '검색어 해석 일부 누락', '기본 단어 검색'],
    ['degraded-dense', '의미 검색 일부 누락', '사용 가능한 정보'],
    ['degraded-snapshot', '검색 기록 저장 실패', '문의와 후속 교정'],
  ];

  for (const [state, title, description] of cases) {
    const html = renderNotices(state);
    assert.ok(html.includes('일부 기능 누락'));
    assert.ok(html.includes(title));
    assert.ok(html.includes(description));
    assert.match(html, /aria-label="검색 기능 누락 안내"/);
  }
});

test('검수 규칙은 적용 사실만 표시하고 내부 상세를 노출하지 않는다', () => {
  const html = renderNotices('review-rule');

  assert.ok(html.includes('검수 규칙 적용'));
  assert.ok(html.includes('아카이브 팀이 확인한 규칙'));
  assert.doesNotMatch(html, /rule[_ -]?id|condition|JSON|오류 코드/i);
});

test('결과와 Preview가 같은 상태·고지 문구를 사용한다', () => {
  const resultsHtml = renderNotices('degraded-snapshot', 'results');
  const previewHtml = renderNotices('degraded-snapshot', 'preview');

  for (const text of ['검색 기록 저장 실패', '문의와 후속 교정']) {
    assert.ok(resultsHtml.includes(text));
    assert.ok(previewHtml.includes(text));
  }
  assert.match(resultsHtml, /data-variant="results"/);
  assert.match(previewHtml, /data-variant="preview"/);
});
