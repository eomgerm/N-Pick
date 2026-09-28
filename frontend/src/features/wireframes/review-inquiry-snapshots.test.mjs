import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.endsWith('.module.css')) {
      return { url: 'data:text/javascript,export default {}', shortCircuit: true };
    }
    if (specifier.startsWith('@/')) {
      const extension = specifier.endsWith('/reviewer-resolution') ? 'tsx' : 'ts';
      return nextResolve(
        new URL(`../../${specifier.slice(2)}.${extension}`, import.meta.url).href,
        context,
      );
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url.endsWith('.tsx')) {
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

const { SearchInterpretation } = await import('./review-inquiry-snapshots.tsx');
const { inquiries } = await import('./reviewer-inquiries.ts');

test('검색 해석을 한 번만 파싱하고 계산한 요약을 그대로 렌더한다', (context) => {
  const value = inquiries[0].initialResolution;
  const parse = JSON.parse;
  let reads = 0;
  context.mock.method(JSON, 'parse', (text, ...args) => {
    if (text === value) reads++;
    return parse(text, ...args);
  });
  const html = renderToStaticMarkup(createElement(SearchInterpretation, { value }));
  assert.equal(reads, 1);
  assert.match(html, /경부고속도로/);
  assert.match(html, /방송일 2025.09.01 ~ 2025.10.10/);
});

test('기록 없음과 파싱·요약 계산 실패를 구분해서 렌더한다', () => {
  const render = (value) => renderToStaticMarkup(createElement(SearchInterpretation, { value }));
  assert.match(render(null), /저장된 검색 해석이 없습니다/);
  assert.match(render(''), /저장된 검색 해석이 없습니다/);
  for (const value of ['invalid', '{}', 'null', '{"date_windows":{}}']) {
    assert.match(render(value), /저장된 검색 해석을 확인할 수 없습니다/);
  }
});
